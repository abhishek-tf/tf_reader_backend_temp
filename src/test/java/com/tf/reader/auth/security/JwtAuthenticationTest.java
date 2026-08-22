package com.tf.reader.auth.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.tf.reader.TestcontainersConfiguration;
import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.auth.token.JwtProperties;
import com.tf.reader.auth.token.JwtTokenService;

/**
 * The whole request-time path, end to end through the real filter chain:
 * bearer token → decoder → validator → converter → SecurityContext → controller.
 *
 * <p>Driven through the real contract endpoint {@code GET /api/v1/auth/me}, so nothing exists
 * purely to be tested. The endpoint's own contract is covered by {@code AuthMeTest}; this class
 * is about the authentication mechanics in front of it.
 */
@SpringBootTest(properties = {"tf.security.jwt.secret=" + JwtAuthenticationTest.SECRET, "tf.security.jwt.access-token-ttl=1h"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class JwtAuthenticationTest {

	static final String SECRET = "a-test-only-signing-secret-of-sufficient-length-0123456789";
	private static final String OTHER_SECRET = "a-different-secret-of-sufficient-length-9876543210abc";

	private static final TnfUser MEMBER = new TnfUser("usr_6712ab", UserType.INSTITUTION,
			"inst_imperial", List.of("MEMBER"), List.of("col_medicine"));

	@Autowired
	private MockMvc mockMvc;

	@Test
	void aValidTokenAuthenticatesAndReachesTheController() throws Exception {
		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tokenFor(MEMBER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value("usr_6712ab"))
				.andExpect(jsonPath("$.institutionId").value("inst_imperial"))
				.andExpect(jsonPath("$.type").value("INSTITUTION"))
				.andExpect(jsonPath("$.roles[0]").value("MEMBER"))
				.andExpect(jsonPath("$.collections[0]").value("col_medicine"));
	}

	@Test
	void everyRoleSurvivesIntoTheAuthenticatedIdentity() throws Exception {
		// That roles become ROLE_-prefixed authorities is asserted in CurrentUserJwtConverterTest,
		// against the Authentication itself. Here it matters only that several reach the far side
		// of the filter chain intact.
		TnfUser admin = new TnfUser("usr_b920fe", UserType.INSTITUTION, "inst_imperial",
				List.of("MEMBER", "ADMIN"), List.of("col_medicine"));

		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tokenFor(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roles[0]").value("MEMBER"))
				.andExpect(jsonPath("$.roles[1]").value("ADMIN"));
	}

	@Test
	void anIndividualSubscriberHasNoInstitution() throws Exception {
		TnfUser individual = new TnfUser("usr_9f01cd", UserType.INDIVIDUAL, null,
				List.of("SUBSCRIBER"), List.of("col_open"));

		// Asserted on the raw body: jsonPath's doesNotExist() also passes for an explicit null,
		// which is a different thing on the wire from an absent field.
		String body = mockMvc.perform(get("/api/v1/auth/me")
						.header("Authorization", "Bearer " + tokenFor(individual)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.type").value("INDIVIDUAL"))
				.andReturn().getResponse().getContentAsString();

		org.assertj.core.api.Assertions.assertThat(body).doesNotContain("institutionId");
	}

	@Test
	void theClientCannotOverrideItsOwnIdentity() throws Exception {
		// The single most important test here. A caller supplying another user's id and another
		// institution's id gets its own identity back, because the token is the only source.
		mockMvc.perform(get("/api/v1/auth/me")
						.queryParam("userId", "usr_admin")
						.queryParam("institutionId", "inst_dsu")
						.header("Authorization", "Bearer " + tokenFor(MEMBER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value("usr_6712ab"))
				.andExpect(jsonPath("$.institutionId").value("inst_imperial"));
	}

	@Test
	void noAuthorizationHeaderIsUnauthenticated() throws Exception {
		mockMvc.perform(get("/api/v1/auth/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_MISSING"))
				.andExpect(jsonPath("$.traceId").isNotEmpty());
	}

	@Test
	void anExpiredTokenIsRefusedAsExpired() throws Exception {
		// Minted an hour and a half ago with a one-hour life.
		String expired = JwtTokenService.forTest(SECRET, Duration.ofHours(1), Clock.fixed(Instant.now().minus(Duration.ofMinutes(90)), ZoneOffset.UTC))
				.issue(MEMBER).token();

		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + expired))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
	}

	@Test
	void aTokenSignedWithAnotherSecretIsRefused() throws Exception {
		String foreign = JwtTokenService.forTest(OTHER_SECRET, Duration.ofHours(1), Clock.systemUTC()).issue(MEMBER).token();

		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + foreign))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
	}

	@Test
	void aTamperedPayloadIsRefused() throws Exception {
		// Re-encode the payload with an escalated role and keep the original signature - the
		// attack the signature exists to stop.
		String[] parts = tokenFor(MEMBER).split("\\.");
		String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
		String tampered = parts[0] + "."
				+ java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
						payload.replace("\"MEMBER\"", "\"ADMIN\"").getBytes())
				+ "." + parts[2];

		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tampered))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
	}

	@Test
	void aMalformedTokenIsRefusedRatherThanCrashing() throws Exception {
		// A 500 here would turn a bad client into an error in our logs, and a stack trace into
		// a response body.
		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not-a-jwt"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_INVALID"));

		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer a.b.c"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
	}

	@Test
	void theSamlSignInRouteStaysPublic() throws Exception {
		// The JWT filter must not stand in front of the way you get a JWT.
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.post("/api/v1/auth/saml/start")
						.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
						.content("{\"institutionId\":\"inst_imperial\"}"))
				.andExpect(status().isOk());
	}

	@Test
	void theSamlEntryPointStaysPublic() throws Exception {
		mockMvc.perform(get("/saml2/authenticate").queryParam("registrationId", "tf-reader"))
				.andExpect(status().is3xxRedirection());
	}

	private String tokenFor(TnfUser user) {
		return JwtTokenService.forTest(SECRET, Duration.ofHours(1), Clock.systemUTC())
				.issue(user).token();
	}
}
