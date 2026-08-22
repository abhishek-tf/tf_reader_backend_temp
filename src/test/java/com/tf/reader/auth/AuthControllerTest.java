package com.tf.reader.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.tf.reader.auth.repository.MockInstitutionRepository;
import com.tf.reader.auth.token.JwtProperties;
import com.tf.reader.auth.token.JwtTokenService;
import com.tf.reader.auth.token.TokenService;
import com.tf.reader.auth.transaction.AuthTransactionStore;
import com.tf.reader.common.error.GlobalExceptionHandler;

/**
 * Slice test of the endpoint that starts SAML. Spring Security's SAML filters are not in this
 * slice - {@link UserSecurityConfig} needs a relying party registration
 * and is covered by {@code SamlRelyingPartyRegistrationTest} instead.
 */
@WebMvcTest(AuthController.class)
// The security filter chain is deliberately out of this slice: SecurityConfig needs a
// relying party registration, and it is covered by SamlRelyingPartyRegistrationTest instead.
// This test is about the endpoint's own contract.
@AutoConfigureMockMvc(addFilters = false)
@Import({ AuthTransactionStore.class, MockInstitutionRepository.class, GlobalExceptionHandler.class,
		AuthControllerTest.FixedClockConfig.class })
class AuthControllerTest {

	// Deliberately sub-second, to prove timestamps reach the wire as whole seconds.
	private static final Instant NOW = Instant.parse("2026-08-12T14:42:00.123456Z");

	@Autowired
	private MockMvc mockMvc;

	@TestConfiguration
	static class FixedClockConfig {

		@Bean
		Clock clock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		/**
		 * The controller now also issues a token, for {@code /auth/me}. This slice covers
		 * {@code /saml/start}, which does not mint one - but the bean still has to exist for the
		 * controller to be constructed.
		 */
		@Bean
		TokenService tokenService(Clock clock) {
			return new JwtTokenService(
					new JwtProperties("a-test-only-signing-secret-of-sufficient-length-0123456789",
							Duration.ofHours(1)),
					clock);
		}
	}

	@Test
	void startsASamlTransactionForASeededInstitution() throws Exception {
		mockMvc.perform(post("/api/v1/auth/saml/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "institutionId": "inst_imperial", "idpHint": "imperial-sso" }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authTxnId").value(org.hamcrest.Matchers.startsWith("authTxn_")))
				.andExpect(jsonPath("$.institution.institutionId").value("inst_imperial"))
				.andExpect(jsonPath("$.institution.name").value("Imperial College"))
				.andExpect(jsonPath("$.serverTime").value("2026-08-12T14:42:00Z"))
				.andExpect(jsonPath("$.expiresAt").value("2026-08-12T14:52:00Z"));
	}

	@Test
	void theAuthorizationUrlPointsAtTheOneSharedRegistration() throws Exception {
		// Every institution must produce the same registrationId. A per-institution
		// registration id appearing here is the architecture regressing.
		for (String institutionId : new String[] { "inst_imperial", "inst_dsu", "inst_xyz" }) {
			mockMvc.perform(post("/api/v1/auth/saml/start")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{ \"institutionId\": \"" + institutionId + "\" }"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.authorizationUrl")
							.value(org.hamcrest.Matchers.startsWith(
									"/saml2/authenticate?registrationId=tf-reader&authTxn=authTxn_")));
		}
	}

	@Test
	void mintsNoTokenAndNoSession() throws Exception {
		// This endpoint only starts a SAML flow; nobody is authenticated yet. The token is minted
		// at the ACS, once an assertion has been validated - never here, where the only input is
		// an institution id anyone could type.
		mockMvc.perform(post("/api/v1/auth/saml/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "institutionId": "inst_imperial" }
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").doesNotExist())
				.andExpect(jsonPath("$.accessToken").doesNotExist())
				.andExpect(jsonPath("$.sessionId").doesNotExist())
				.andExpect(jsonPath("$.user").doesNotExist());
	}

	@Test
	void authMeRefusesAnIdentityThatDidNotComeFromAToken() throws Exception {
		// This slice runs with the filter chain switched off, which is exactly the shape of the
		// problem: a request reaches the controller with no CurrentUser, because whatever
		// authenticated it was not our bearer token. @AuthenticationPrincipal then resolves to
		// null, and dereferencing it answered 500 with a NullPointerException where an identity
		// should have been. It must refuse instead.
		mockMvc.perform(get("/api/v1/auth/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_MISSING"))
				.andExpect(jsonPath("$.userId").doesNotExist())
				.andExpect(jsonPath("$.token").doesNotExist());
	}

	@Test
	void refusesAnUnknownInstitution() throws Exception {
		mockMvc.perform(post("/api/v1/auth/saml/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "institutionId": "inst_nowhere" }
								"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.traceId").isNotEmpty());
	}

	@Test
	void refusesAMissingInstitutionId() throws Exception {
		mockMvc.perform(post("/api/v1/auth/saml/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "idpHint": "imperial-sso" }
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.message").value("institutionId is required"));
	}

	@Test
	void refusesABlankInstitutionId() throws Exception {
		mockMvc.perform(post("/api/v1/auth/saml/start")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "institutionId": "   " }
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void refusesAnAbsentBody() throws Exception {
		mockMvc.perform(post("/api/v1/auth/saml/start").contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}
}
