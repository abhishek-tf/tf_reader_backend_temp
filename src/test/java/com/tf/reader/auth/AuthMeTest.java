package com.tf.reader.auth;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import com.tf.reader.TestcontainersConfiguration;
import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.auth.security.TnfJwtValidator;
import com.tf.reader.auth.token.JwtProperties;
import com.tf.reader.auth.token.JwtTokenService;

/**
 * {@code GET /api/v1/auth/me} against the real filter chain.
 *
 * <p>The clock is fixed so {@code serverTime} and {@code expiresAt} can be asserted exactly
 * rather than approximately - the two fields the app counts down from.
 */
@SpringBootTest(properties = "tnf.auth.jwt.secret=" + AuthMeTest.SECRET)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthMeTest {

	static final String SECRET = "a-test-only-signing-secret-of-sufficient-length-0123456789";

	private static final Instant NOW = Instant.parse("2026-08-13T14:42:00Z");

	private static final TnfUser MEMBER = new TnfUser("usr_6712ab", UserType.INSTITUTION,
			"inst_imperial", List.of("MEMBER"), List.of("col_medicine"));

	private static final TnfUser INDIVIDUAL = new TnfUser("usr_9f01cd", UserType.INDIVIDUAL, null,
			List.of("SUBSCRIBER"), List.of("col_open"));

	@Autowired
	private MockMvc mockMvc;

	/**
	 * Freezes the application clock. Both the validator and the controller take this bean, so a
	 * token minted at NOW is still valid at NOW.
	 *
	 * <p>Named differently from {@code ClockConfig.clock} on purpose: same bean name would be an
	 * override, which Boot forbids by default. A distinct name plus {@code @Primary} adds a
	 * candidate and wins injection.
	 */
	@TestConfiguration
	static class FixedClockConfig {

		@Bean
		@Primary
		Clock fixedTestClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	@Test
	void returnsTheIdentityFromTheToken() throws Exception {
		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tokenFor(MEMBER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value("usr_6712ab"))
				.andExpect(jsonPath("$.type").value("INSTITUTION"))
				.andExpect(jsonPath("$.institutionId").value("inst_imperial"))
				.andExpect(jsonPath("$.roles[0]").value("MEMBER"))
				.andExpect(jsonPath("$.collections[0]").value("col_medicine"));
	}

	@Test
	void serverTimeComesFromTheServerClock() throws Exception {
		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tokenFor(MEMBER)))
				.andExpect(jsonPath("$.serverTime").value("2026-08-13T14:42:00Z"));
	}

	@Test
	void expiresAtDescribesTheNewlyIssuedToken() throws Exception {
		// One hour from now, per JwtProperties - not the incoming token's expiry, and not a
		// duration written down a second time in the controller.
		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tokenFor(MEMBER)))
				.andExpect(jsonPath("$.expiresAt").value("2026-08-13T15:42:00Z"));
	}

	@Test
	void theReissuedTokenIsUsableAndCarriesTheSameIdentity() throws Exception {
		// Proves the session can actually slide: the token handed back authenticates on its own.
		String body = mockMvc.perform(get("/api/v1/auth/me")
						.header("Authorization", "Bearer " + tokenFor(MEMBER)))
				.andReturn().getResponse().getContentAsString();
		String reissued = body.replaceAll(".*\"token\"\\s*:\\s*\"([^\"]+)\".*", "$1");

		// The decoder is pinned to the test's clock. Left at Spring's defaults it would judge
		// expiry by the SYSTEM clock, and this token was minted by a fixed one - so the test would
		// pass for an hour after NOW and then fail forever with "Jwt expired", which reads like a
		// production bug rather than a test pinned to a date.
		NimbusJwtDecoder decoder =
				NimbusJwtDecoder.withSecretKey(new JwtProperties(SECRET, null).signingKey())
						.macAlgorithm(MacAlgorithm.HS256)
						.build();
		decoder.setJwtValidator(new TnfJwtValidator(Clock.fixed(NOW, ZoneOffset.UTC)));

		Jwt decoded = decoder.decode(reissued);

		assertThat(decoded.getClaimAsString("userId")).isEqualTo("usr_6712ab");
		assertThat(decoded.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));

		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + reissued))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value("usr_6712ab"));
	}

	@Test
	void omitsInstitutionIdEntirelyForAnIndividual() throws Exception {
		// The contract shows the field absent. Asserted on the raw body, because jsonPath's
		// doesNotExist() also passes for an explicit null - which is a different thing on the
		// wire and reads as "institution unknown" rather than "no institution".
		String body = mockMvc.perform(get("/api/v1/auth/me")
						.header("Authorization", "Bearer " + tokenFor(INDIVIDUAL)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.type").value("INDIVIDUAL"))
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("institutionId");
	}

	@Test
	void returnsExactlyTheContractFields() throws Exception {
		// Nothing invented beyond the documented shape except token, which is flagged as an
		// open contract point on AuthMeResponse.
		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tokenFor(MEMBER)))
				.andExpect(jsonPath("$.*", org.hamcrest.Matchers.hasSize(8)))
				.andExpect(jsonPath("$.refreshToken").doesNotExist())
				.andExpect(jsonPath("$.accessToken").doesNotExist())
				.andExpect(jsonPath("$.idToken").doesNotExist());
	}

	@Test
	void queryParametersCannotChangeWhoYouAre() throws Exception {
		// The endpoint takes no parameters at all, so these are simply ignored. The test exists
		// because "ignored" is a claim worth proving rather than asserting in a comment.
		mockMvc.perform(get("/api/v1/auth/me")
						.queryParam("userId", "usr_admin")
						.queryParam("institutionId", "inst_dsu")
						.queryParam("roles", "ADMIN")
						.queryParam("type", "INDIVIDUAL")
						.queryParam("collections", "col_everything")
						.header("Authorization", "Bearer " + tokenFor(MEMBER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value("usr_6712ab"))
				.andExpect(jsonPath("$.institutionId").value("inst_imperial"))
				.andExpect(jsonPath("$.roles[0]").value("MEMBER"))
				.andExpect(jsonPath("$.type").value("INSTITUTION"))
				.andExpect(jsonPath("$.collections[0]").value("col_medicine"));
	}

	@Test
	void headersCannotChangeWhoYouAre() throws Exception {
		// Query parameters are covered above; headers are the other obvious place a client would
		// try. Nothing reads a header for identity, and that has to stay true.
		mockMvc.perform(get("/api/v1/auth/me")
						.header("Authorization", "Bearer " + tokenFor(MEMBER))
						.header("X-User-Id", "usr_admin")
						.header("X-Institution-Id", "inst_dsu")
						.header("X-Roles", "ADMIN")
						.header("X-Forwarded-User", "usr_admin"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value("usr_6712ab"))
				.andExpect(jsonPath("$.institutionId").value("inst_imperial"))
				.andExpect(jsonPath("$.roles[0]").value("MEMBER"))
				.andExpect(jsonPath("$.roles", org.hamcrest.Matchers.hasSize(1)));
	}

	@Test
	void aRequestBodyCannotChangeWhoYouAre() throws Exception {
		// GET /auth/me takes no body. One sent anyway must be ignored rather than bound.
		mockMvc.perform(get("/api/v1/auth/me")
						.header("Authorization", "Bearer " + tokenFor(MEMBER))
						.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
						.content("""
								{ "userId": "usr_admin", "institutionId": "inst_dsu",
								  "roles": ["ADMIN"], "type": "INDIVIDUAL" }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value("usr_6712ab"))
				.andExpect(jsonPath("$.institutionId").value("inst_imperial"))
				.andExpect(jsonPath("$.type").value("INSTITUTION"))
				.andExpect(jsonPath("$.roles[0]").value("MEMBER"));
	}

	@Test
	void aSecondTokenInAnotherHeaderIsNotConsulted() throws Exception {
		// An ADMIN token in a header nobody reads must not escalate a MEMBER request. Only the
		// Authorization header is an authentication input.
		TnfUser admin = new TnfUser("usr_b920fe", UserType.INSTITUTION, "inst_imperial",
				List.of("MEMBER", "ADMIN"), List.of("col_medicine"));

		mockMvc.perform(get("/api/v1/auth/me")
						.header("Authorization", "Bearer " + tokenFor(MEMBER))
						.header("X-Authorization", "Bearer " + tokenFor(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value("usr_6712ab"))
				.andExpect(jsonPath("$.roles", org.hamcrest.Matchers.hasSize(1)));
	}

	@Test
	void anExpiredTokenIsRefusedAndNoTokenIsIssued() throws Exception {
		// Minted 90 minutes before the frozen clock, so it has expired by NOW.
		String expired = new JwtTokenService(new JwtProperties(SECRET, Duration.ofHours(1)),
				Clock.fixed(NOW.minus(Duration.ofMinutes(90)), ZoneOffset.UTC)).issue(MEMBER).token();

		String body = mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + expired))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))
				.andReturn().getResponse().getContentAsString();

		// An expired token must never be exchangeable for a fresh one. The controller never ran.
		assertThat(body).doesNotContain("token\":\"ey");
	}

	@Test
	void aTamperedTokenNeverReachesTheController() throws Exception {
		String[] parts = tokenFor(MEMBER).split("\\.");
		String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
		String tampered = parts[0] + "."
				+ java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
						payload.replace("usr_6712ab", "usr_admin1").getBytes())
				+ "." + parts[2];

		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tampered))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_INVALID"))
				.andExpect(jsonPath("$.userId").doesNotExist());
	}

	@Test
	void noTokenAtAllIsRefused() throws Exception {
		mockMvc.perform(get("/api/v1/auth/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_MISSING"));
	}

	private String tokenFor(TnfUser user) {
		return new JwtTokenService(new JwtProperties(SECRET, Duration.ofHours(1)),
				Clock.fixed(NOW, ZoneOffset.UTC)).issue(user).token();
	}
}
