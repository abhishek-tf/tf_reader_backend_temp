package com.tf.reader.auth.saml;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AssertionAuthentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2ResponseAssertionAccessor;

import com.tf.reader.TestcontainersConfiguration;
import com.tf.reader.auth.dto.TokenResponse;
import com.tf.reader.auth.token.AuthorizationCodeStore;
import com.tf.reader.auth.transaction.AuthTransactionStore;
import com.tf.reader.catalogue.api.InstitutionLookup;
import com.tf.reader.catalogue.api.InstitutionRef;

/**
 * What the ACS leaves behind, and where it sends the browser - both matter as much as what it
 * mints. The browser is mid-redirect from the IdP here, so success and refusal both end at a
 * deep link, never at a JSON body.
 */
@SpringBootTest(properties = { "tf.security.jwt.secret=" + SamlAuthenticationSuccessHandlerTest.SECRET })
@Import({ TestcontainersConfiguration.class, SamlAuthenticationSuccessHandlerTest.FixedTestConfig.class })
class SamlAuthenticationSuccessHandlerTest {

	static final String SECRET = "a-test-only-signing-secret-of-sufficient-length-0123456789";

	private static final String EMAIL_CLAIM =
			"http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress";

	@Autowired
	private SamlAuthenticationSuccessHandler handler;

	@Autowired
	private AuthTransactionStore transactions;

	@Autowired
	private AuthorizationCodeStore authorizationCodes;

	@TestConfiguration
	static class FixedTestConfig {

		@Bean
		@Primary
		Clock fixedTestClock() {
			return Clock.fixed(Instant.parse("2026-08-13T09:00:00Z"), ZoneOffset.UTC);
		}

		/** A fixed stand-in for the real, Mongo-backed lookup. */
		@Bean
		@Primary
		InstitutionLookup institutions() {
			Map<String, InstitutionRef> institutions = Map.of(
					"inst_7f3", new InstitutionRef("inst_7f3", "Imperial College London"),
					"inst_ucl", new InstitutionRef("inst_ucl", "University College London"));
			return institutionId -> Optional.ofNullable(institutions.get(institutionId));
		}
	}

	@Test
	void aSuccessfulSignInRedirectsWithACodeAndEndsTheSession() throws Exception {
		String relayState = transactions.open("inst_7f3").id();
		MockHttpSession session = new MockHttpSession();
		MockHttpServletRequest request = requestWith(session, relayState);
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(request, response, samlAuthentication());

		assertThat(response.getStatus()).isEqualTo(302);
		assertThat(response.getRedirectedUrl())
				.startsWith(SamlAuthenticationSuccessHandler.DEEP_LINK_CALLBACK + "?code=");
		assertThat(session.isInvalid())
				.describedAs("the sign-in session must not outlive the code it produced")
				.isTrue();
	}

	@Test
	void theRedeemedCodeCarriesAUsableTokenPair() throws Exception {
		String relayState = transactions.open("inst_ucl").id();
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(requestWith(new MockHttpSession(), relayState), response,
				samlAuthentication());

		String code = response.getRedirectedUrl().substring(
				(SamlAuthenticationSuccessHandler.DEEP_LINK_CALLBACK + "?code=").length());

		TokenResponse tokens = authorizationCodes.consume(code).orElseThrow();
		assertThat(tokens.accessToken()).isNotBlank();
		assertThat(tokens.refreshToken()).isNotBlank();
		assertThat(tokens.expiresIn()).isPositive();

		// Single use: the same code cannot be redeemed twice.
		assertThat(authorizationCodes.consume(code)).isEmpty();
	}

	@Test
	void aRefusedSignInRedirectsWithAnErrorAndEndsTheSessionToo() throws Exception {
		// The refusal path is reached AFTER Spring Security has already written the authentication
		// into the session, so a sign-in we rejected must not leave an authenticated one behind.
		MockHttpSession session = new MockHttpSession();
		MockHttpServletRequest request = requestWith(session, "authTxn_neverIssued");
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(request, response, samlAuthentication());

		assertThat(response.getStatus()).isEqualTo(302);
		assertThat(response.getRedirectedUrl()).isEqualTo(
				SamlAuthenticationSuccessHandler.DEEP_LINK_CALLBACK + "?error=SAML_AUTHENTICATION_FAILED");
		assertThat(session.isInvalid()).isTrue();
	}

	@Test
	void aSignInWithNoSessionAtAllIsNotAnError() throws Exception {
		// getSession(false) returning null is normal - nothing must blow up on the way out.
		MockHttpServletRequest request = requestWith(null, transactions.open("inst_ucl").id());
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(request, response, samlAuthentication());

		assertThat(response.getStatus()).isEqualTo(302);
	}

	private MockHttpServletRequest requestWith(MockHttpSession session, String relayState) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST",
				"/login/saml2/sso/tf-reader");
		if (session != null) {
			request.setSession(session);
		}
		request.setParameter("RelayState", relayState);
		return request;
	}

	private Authentication samlAuthentication() {
		return new Saml2AssertionAuthentication(
				new StubAssertion("john.doe@example.com",
						Map.of(EMAIL_CLAIM, List.of("john.doe@example.com"))),
				List.of(), "tf-reader");
	}

	private record StubAssertion(String nameId, Map<String, List<Object>> attributes)
			implements Saml2ResponseAssertionAccessor {

		@Override
		public String getNameId() {
			return nameId;
		}

		@Override
		public List<String> getSessionIndexes() {
			return List.of();
		}

		@Override
		public Map<String, List<Object>> getAttributes() {
			return attributes;
		}

		@Override
		public String getResponseValue() {
			return "";
		}
	}
}
