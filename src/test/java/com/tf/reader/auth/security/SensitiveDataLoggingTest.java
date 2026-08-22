package com.tf.reader.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.tf.reader.ContainerisedInfrastructure;
import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.auth.token.JwtProperties;
import com.tf.reader.auth.token.JwtTokenService;

/**
 * Definition of Done #5: no secret, password, token or device key in any log line - "checked by a
 * test, because a debug line will reintroduce it".
 *
 * <p>Captures real application output while exercising the paths that handle sensitive material,
 * then asserts none of it appears. A source-level grep would pass the day somebody adds a logger;
 * this fails.
 */
@SpringBootTest(properties = "tnf.auth.jwt.secret=" + SensitiveDataLoggingTest.SECRET)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SensitiveDataLoggingTest extends ContainerisedInfrastructure {

	static final String SECRET = "a-test-only-signing-secret-of-sufficient-length-0123456789";

	private static final TnfUser MEMBER = new TnfUser("usr_6712ab", UserType.INSTITUTION,
			"inst_imperial", List.of("MEMBER"), List.of("col_medicine"));

	@Autowired
	private MockMvc mockMvc;

	@Test
	void aValidTokenIsNeverWrittenToTheLog(CapturedOutput output) throws Exception {
		String token = tokenFor(MEMBER);

		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token));

		assertThat(output).doesNotContain(token);
		// Nor the signature segment on its own, which is the part that would let it be replayed.
		assertThat(output).doesNotContain(token.substring(token.lastIndexOf('.') + 1));
	}

	@Test
	void theSigningSecretIsNeverWrittenToTheLog(CapturedOutput output) throws Exception {
		// Reached the widest set of code paths first: sign-in, a good token, a bad token.
		mockMvc.perform(post("/api/v1/auth/saml/start")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"institutionId\":\"inst_imperial\"}"));
		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tokenFor(MEMBER)));
		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not-a-jwt"));

		assertThat(output).doesNotContain(SECRET);
	}

	@Test
	void aRejectedTokenIsNotEchoedIntoTheLog(CapturedOutput output) throws Exception {
		// The refusal paths are the tempting place to log "the token that failed" - which would
		// write attacker-supplied material, and sometimes a real token, straight to disk.
		String expired = new JwtTokenService(new JwtProperties(SECRET, Duration.ofHours(1)),
				Clock.fixed(Instant.now().minus(Duration.ofHours(3)), ZoneOffset.UTC))
				.issue(MEMBER).token();

		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + expired));

		assertThat(output).doesNotContain(expired);
	}

	@Test
	void aRejectedSamlResponseIsNotEchoedIntoTheLog(CapturedOutput output) throws Exception {
		// The SAML failure handler logs a reason. A message that interpolated the response would
		// put a signed assertion - an authentication credential - into the log.
		String forged = Base64.getEncoder().encodeToString(
				("<samlp:Response xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\">"
						+ "SENSITIVE-ASSERTION-MARKER</samlp:Response>").getBytes());

		mockMvc.perform(post("/login/saml2/sso/tf-reader")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("SAMLResponse", forged));

		assertThat(output).doesNotContain("SENSITIVE-ASSERTION-MARKER").doesNotContain(forged);
	}

	@Test
	void theAuthenticationTokenMasksItsCredentials() {
		// Spring Security masks credentials in toString(); CurrentUserAuthenticationToken keeps the
		// verified Jwt there, so if that masking were ever lost, any log of an Authentication
		// object would print a live token.
		String token = tokenFor(MEMBER);
		org.springframework.security.oauth2.jwt.Jwt jwt =
				org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
						.header("alg", "HS256").claim("userId", "usr_6712ab").build();

		String printed = new CurrentUserAuthenticationToken(
				new com.tf.reader.auth.model.CurrentUser("usr_6712ab", UserType.INSTITUTION,
						"inst_imperial", List.of("MEMBER"), List.of("col_medicine")),
				jwt, List.of()).toString();

		assertThat(printed).doesNotContain(token);
	}

	private String tokenFor(TnfUser user) {
		return new JwtTokenService(new JwtProperties(SECRET, Duration.ofHours(1)), Clock.systemUTC())
				.issue(user).token();
	}
}
