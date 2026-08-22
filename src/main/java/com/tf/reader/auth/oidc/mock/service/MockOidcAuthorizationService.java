package com.tf.reader.auth.oidc.mock.service;

import com.tf.reader.auth.oidc.mock.config.MockOidcComponent;
import com.tf.reader.auth.oidc.mock.config.MockOidcProperties;
import com.tf.reader.auth.oidc.mock.store.MockAuthorizationCodeStore;

import java.util.Arrays;
import java.util.List;

import org.springframework.util.StringUtils;

import com.tf.reader.auth.oidc.mock.store.MockAuthorizationCodeStore.IssuedCode;

/**
 * The mock provider's authorization endpoint logic: validate the request, then issue a code.
 *
 * <p><b>The validation is not decoration.</b> A mock that accepted any authorization request
 * would hide exactly the class of bug this whole exercise is meant to surface early -
 * a redirect uri that does not match what is registered, a client id typo, a missing
 * {@code openid} scope that silently produces no ID token. Every check here is one a real
 * provider performs, and each one has failed for somebody on their first day against B2C.
 *
 * <p><b>The redirect uri check is the one that matters most.</b> It is compared to the single
 * registered value and must be exactly equal - not a prefix, not "starts with our host". An
 * open redirect here would mean an attacker could have the provider deliver a legitimately
 * issued authorization code to a url they control, which is the classic way an OAuth
 * integration is turned into an account takeover. It is also why the error for a bad redirect
 * uri is <b>not</b> a redirect: sending the error back to an unvalidated uri would be the same
 * hole wearing a different hat.
 */
@MockOidcComponent
public class MockOidcAuthorizationService {

	private static final org.slf4j.Logger log =
			org.slf4j.LoggerFactory.getLogger(MockOidcAuthorizationService.class);

	/** The only response type a code flow uses; the implicit flow is deliberately not supported. */
	static final String RESPONSE_TYPE_CODE = "code";

	/** Without this scope there is no ID token, and the sign-in is not OIDC at all. */
	static final String SCOPE_OPENID = "openid";

	private final MockOidcProperties properties;
	private final MockAuthorizationCodeStore codes;

	public MockOidcAuthorizationService(MockOidcProperties properties,
			MockAuthorizationCodeStore codes) {
		this.properties = properties;
		this.codes = codes;
	}

	/**
	 * Checks an authorization request before a sign-in page is shown.
	 *
	 * @throws MockOidcRequestException if the request is one no code may be issued for
	 */
	public void validateAuthorizationRequest(String clientId, String redirectUri,
			String responseType, String scope) {

		// Client and redirect uri first, and in that order, because these two decide whether an
		// error may be redirected at all. Everything after them is safe to report to the client.
		if (!StringUtils.hasText(clientId) || !clientId.equals(properties.clientId())) {
			log.warn("Mock OIDC authorization request refused: unknown client_id");
			throw new MockOidcRequestException("unauthorized_client",
					"Unknown client_id.");
		}
		if (!StringUtils.hasText(redirectUri) || !redirectUri.equals(properties.redirectUri())) {
			log.warn("Mock OIDC authorization request refused: redirect_uri does not match the "
					+ "registered value");
			throw new MockOidcRequestException("invalid_request",
					"redirect_uri does not match the one registered for this client.");
		}
		if (!RESPONSE_TYPE_CODE.equals(responseType)) {
			throw new MockOidcRequestException("unsupported_response_type",
					"Only response_type=code is supported.");
		}
		if (!scopes(scope).contains(SCOPE_OPENID)) {
			throw new MockOidcRequestException("invalid_scope",
					"The openid scope is required; without it there is no ID token.");
		}
	}

	/**
	 * Authenticates the pre-populated user and issues a code.
	 *
	 * <p>Re-validates rather than trusting that the consent page was reached legitimately: the
	 * form posts back the parameters it was rendered with, and a hand-written POST is free to
	 * change them. Validating once, at render time, would be a check an attacker simply skips.
	 */
	public IssuedCode authorize(String clientId, String redirectUri, String responseType,
			String scope, String nonce) {

		validateAuthorizationRequest(clientId, redirectUri, responseType, scope);

		IssuedCode code = codes.issue(clientId, redirectUri, scope, nonce, properties.user());
		// The code itself is never logged: short-lived and single-use, but a credential.
		log.info("Mock OIDC authorization code created for {} (expires {})",
				properties.user().sub(), code.expiresAt());
		return code;
	}

	private static List<String> scopes(String scope) {
		return StringUtils.hasText(scope) ? Arrays.asList(scope.trim().split("\\s+")) : List.of();
	}

	/**
	 * A refusal by the mock provider, carrying an OAuth 2.0 error code.
	 *
	 * <p>Note that this is <b>not</b> the application's {@code ApiException}: the mock is
	 * pretending to be somebody else's server, and it must answer in the shape the OAuth 2.0
	 * specification defines rather than in our internal error contract. Blurring the two is how a
	 * mock stops resembling the thing it stands in for.
	 */
	public static class MockOidcRequestException extends RuntimeException {

		private final String error;

		public MockOidcRequestException(String error, String description) {
			super(description);
			this.error = error;
		}

		public String error() {
			return error;
		}
	}
}
