package com.tf.reader.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.tf.reader.auth.dto.TokenResponse;
import com.tf.reader.auth.entity.ReaderSession;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.auth.service.ReaderSessionService;
import com.tf.reader.auth.service.ReaderSessionService.IssuedRefreshToken;
import com.tf.reader.auth.token.AuthorizationCodeStore;
import com.tf.reader.auth.token.JwtTokenService;
import com.tf.reader.auth.token.TokenService;
import com.tf.reader.auth.transaction.AuthTransactionStore;
import com.tf.reader.catalogue.api.InstitutionLookup;
import com.tf.reader.catalogue.api.InstitutionRef;
import com.tf.reader.common.error.GlobalExceptionHandler;

/**
 * Slice test of the endpoint that starts SAML. Spring Security's SAML filters are not in this
 * slice - {@link com.tf.reader.auth.security.SecurityConfig} needs a relying party registration
 * and is covered by {@code SamlRelyingPartyRegistrationTest} instead.
 */
@WebMvcTest(AuthController.class)
// The security filter chain is deliberately out of this slice: SecurityConfig needs a
// relying party registration, and it is covered by SamlRelyingPartyRegistrationTest instead.
// This test is about the endpoint's own contract.
@AutoConfigureMockMvc(addFilters = false)
@Import({ AuthTransactionStore.class, GlobalExceptionHandler.class,
		AuthControllerTest.FixedClockConfig.class })
class AuthControllerTest {

	// Deliberately sub-second, to prove timestamps reach the wire as whole seconds.
	private static final Instant NOW = Instant.parse("2026-08-12T14:42:00.123456Z");

	@Autowired
	private MockMvc mockMvc;

	// The refresh/exchange endpoints live on this controller too, but neither is exercised by
	// this slice's tests - the beans only need to exist for the controller to be constructed.
	@MockitoBean
	private ReaderSessionService readerSessions;

	@MockitoBean
	private AuthorizationCodeStore authorizationCodes;

	@TestConfiguration
	static class FixedClockConfig {

		@Bean
		Clock clock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		/**
		 * The controller now also issues a token, for {@code /auth/refresh}. This slice covers
		 * {@code /saml/start}, which does not mint one - but the bean still has to exist for the
		 * controller to be constructed.
		 */
		@Bean
		TokenService tokenService(Clock clock) {
			return JwtTokenService.forTest("a-test-only-signing-secret-of-sufficient-length-0123456789", Duration.ofHours(1), clock);
		}

		/** A fixed stand-in for the real, Mongo-backed lookup - this slice never starts Mongo. */
		@Bean
		InstitutionLookup institutions() {
			Map<String, InstitutionRef> institutions = Map.of(
					"inst_7f3", new InstitutionRef("inst_7f3", "Imperial College London"),
					"inst_ucl", new InstitutionRef("inst_ucl", "University College London"),
					"inst_leeds", new InstitutionRef("inst_leeds", "University of Leeds"));
			return institutionId -> Optional.ofNullable(institutions.get(institutionId));
		}
	}

	@Test
	void startsASamlTransactionForASeededInstitution() throws Exception {
		mockMvc.perform(post("/api/v1/auth/saml/start")
						.param("institutionId", "inst_7f3")
						.param("idpHint", "imperial-sso"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authTxnId").value(org.hamcrest.Matchers.startsWith("authTxn_")))
				.andExpect(jsonPath("$.institution.institutionId").value("inst_7f3"))
				.andExpect(jsonPath("$.institution.name").value("Imperial College London"))
				.andExpect(jsonPath("$.serverTime").value("2026-08-12T14:42:00Z"))
				.andExpect(jsonPath("$.expiresAt").value("2026-08-12T14:52:00Z"));
	}

	@Test
	void theAuthorizationUrlPointsAtTheOneSharedRegistration() throws Exception {
		// Every institution must produce the same registrationId. A per-institution
		// registration id appearing here is the architecture regressing.
		for (String institutionId : new String[] { "inst_7f3", "inst_ucl", "inst_leeds" }) {
			mockMvc.perform(post("/api/v1/auth/saml/start").param("institutionId", institutionId))
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
		mockMvc.perform(post("/api/v1/auth/saml/start").param("institutionId", "inst_7f3"))
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
		mockMvc.perform(post("/api/v1/auth/saml/start").param("institutionId", "inst_nowhere"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.traceId").isNotEmpty());
	}

	@Test
	void refusesAMissingInstitutionId() throws Exception {
		mockMvc.perform(post("/api/v1/auth/saml/start").param("idpHint", "imperial-sso"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.message").value("institutionId is required"));
	}

	@Test
	void refusesABlankInstitutionId() throws Exception {
		mockMvc.perform(post("/api/v1/auth/saml/start").param("institutionId", "   "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.message").value("institutionId is required"));
	}

	@Test
	void exchangesACodeForTheTokenPairItWasIssuedFor() throws Exception {
		when(authorizationCodes.consume("good-code"))
				.thenReturn(Optional.of(new TokenResponse("access-abc", "refresh-xyz", 900)));

		mockMvc.perform(post("/api/v1/auth/token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"code\": \"good-code\" }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").value("access-abc"))
				.andExpect(jsonPath("$.refreshToken").value("refresh-xyz"))
				.andExpect(jsonPath("$.expiresIn").value(900));
	}

	@Test
	void refusesAnUnknownOrAlreadyUsedCode() throws Exception {
		when(authorizationCodes.consume("stale-code")).thenReturn(Optional.empty());

		mockMvc.perform(post("/api/v1/auth/token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"code\": \"stale-code\" }"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
	}

	@Test
	void refreshRotatesTheSessionAndMintsAFreshAccessToken() throws Exception {
		ReaderSession claimed = new ReaderSession();
		claimed.setUserId("usr_6712ab");
		claimed.setType(UserType.INSTITUTION);
		claimed.setInstitutionId("inst_7f3");
		claimed.setRoles(List.of("MEMBER"));
		claimed.setCollections(List.of("col_medicine"));

		when(readerSessions.revokeForExchange("good-refresh")).thenReturn(Optional.of(claimed));
		when(readerSessions.createSession(any()))
				.thenReturn(new IssuedRefreshToken("rotated-refresh", claimed));

		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"refreshToken\": \"good-refresh\" }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").value("rotated-refresh"))
				.andExpect(jsonPath("$.expiresIn").value(3600));
	}

	@Test
	void refusesAnUnknownOrExpiredRefreshToken() throws Exception {
		when(readerSessions.revokeForExchange("stale-refresh")).thenReturn(Optional.empty());

		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"refreshToken\": \"stale-refresh\" }"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
	}
}
