package com.tf.reader.auth.saml;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AssertionAuthentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2ResponseAssertionAccessor;

import com.tf.reader.auth.repository.MockInstitutionRepository;
import com.tf.reader.auth.repository.MockUserRepository;
import com.tf.reader.auth.token.JwtProperties;
import com.tf.reader.auth.token.JwtTokenService;
import com.tf.reader.auth.transaction.AuthTransactionStore;

import tools.jackson.databind.json.JsonMapper;

/**
 * What the ACS leaves behind, which matters as much as what it returns.
 *
 * <p>Spring Security persists the SAML authentication into the HTTP session <b>before</b> this
 * handler is called, on both the success and the refusal path. That session is a credential for an
 * identity that never passed through the JWT validator and that the token design has no way to
 * expire - so sign-in has to end it. The API chain being stateless means such a session cannot
 * authenticate anything today; ending it here means there is no stale credential to authenticate
 * with tomorrow either.
 */
class SamlAuthenticationSuccessHandlerTest {

	private static final String SECRET = "a-test-only-signing-secret-of-sufficient-length-0123456789";

	private static final String EMAIL_CLAIM =
			"http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress";

	private final AuthTransactionStore transactions = new AuthTransactionStore(Clock.systemUTC());

	private final SamlAuthenticationSuccessHandler handler = new SamlAuthenticationSuccessHandler(
			new SamlAuthenticationService(transactions, new MockInstitutionRepository(),
					new SamlUserMapper(new MockUserRepository()),
					new JwtTokenService(new JwtProperties(SECRET, Duration.ofHours(1)),
							Clock.systemUTC()),
					Clock.systemUTC()),
			JsonMapper.builder().build());

	@Test
	void aSuccessfulSignInEndsTheSessionItNeeded() throws Exception {
		String relayState = transactions.open("inst_imperial").id();
		MockHttpSession session = new MockHttpSession();
		MockHttpServletRequest request = requestWith(session, relayState);
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(request, response, samlAuthentication());

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getContentAsString()).contains("\"token\"");
		assertThat(session.isInvalid())
				.describedAs("the sign-in session must not outlive the token it produced")
				.isTrue();
	}

	@Test
	void aRefusedSignInEndsTheSessionToo() throws Exception {
		// The refusal path is reached AFTER Spring Security has already written the authentication
		// into the session, so a sign-in we rejected must not leave an authenticated one behind.
		MockHttpSession session = new MockHttpSession();
		MockHttpServletRequest request = requestWith(session, "authTxn_neverIssued");
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(request, response, samlAuthentication());

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentAsString()).contains("SAML_AUTHENTICATION_FAILED");
		assertThat(session.isInvalid()).isTrue();
	}

	@Test
	void aSignInWithNoSessionAtAllIsNotAnError() throws Exception {
		// getSession(false) returning null is normal - nothing must blow up on the way out.
		MockHttpServletRequest request = requestWith(null, transactions.open("inst_dsu").id());
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(request, response, samlAuthentication());

		assertThat(response.getStatus()).isEqualTo(200);
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
