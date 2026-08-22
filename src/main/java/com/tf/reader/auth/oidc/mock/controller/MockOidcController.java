package com.tf.reader.auth.oidc.mock.controller;

import com.tf.reader.auth.oidc.mock.config.MockOidcProperties;
import com.tf.reader.auth.oidc.mock.model.MockOidcUser;
import com.tf.reader.auth.oidc.mock.security.MockOidcKeyService;
import com.tf.reader.auth.oidc.mock.service.MockOidcAuthorizationService;
import com.tf.reader.auth.oidc.mock.service.MockOidcTokenService;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import com.tf.reader.auth.oidc.mock.store.MockAuthorizationCodeStore.IssuedCode;
import com.tf.reader.auth.oidc.mock.service.MockOidcAuthorizationService.MockOidcRequestException;

/**
 * The local mock OpenID Connect provider's HTTP surface.
 *
 * <p><b>This is not part of the application. It is pretending to be somebody else's server.</b>
 * It exists so the whole OIDC flow - redirect, sign-in, authorization code, back-channel
 * exchange, signed ID token, JWKS - can be run and demonstrated on a laptop with no tenant, no
 * network and no credentials. Nothing in {@code com.tf.reader.auth.oidc} knows it is here: the
 * relying party reaches it through configured urls, so pointing those at Azure AD B2C is the
 * entire migration and this package can then be deleted without touching a line of client code.
 *
 * <p><b>Never enabled by default.</b> {@code mock-oidc.enabled} must be set explicitly, and
 * {@code SecurityArchitectureTest} asserts that has not drifted - a mock provider switched on by
 * accident is a way to mint identities for arbitrary users.
 *
 * <p>The four endpoints are the ones any OIDC library expects to find:
 *
 * <ul>
 * <li>{@code GET /.well-known/openid-configuration} - discovery</li>
 * <li>{@code GET /oauth2/authorize} - the sign-in page, and {@code POST} to consent</li>
 * <li>{@code POST /oauth2/token} - the back-channel code exchange</li>
 * <li>{@code GET /oauth2/jwks} - the public keys ID tokens are verified against</li>
 * </ul>
 */
// @RestController rather than @MockOidcComponent: Spring MVC's handler detection looks for
// @Controller specifically - as of Spring Framework 7 a type-level @RequestMapping is no longer
// enough - so this one class needs the stereotype and carries the same condition directly.
@RestController
@ConditionalOnProperty(prefix = "mock-oidc", name = "enabled", havingValue = "true")
public class MockOidcController {

	private static final org.slf4j.Logger log =
			org.slf4j.LoggerFactory.getLogger(MockOidcController.class);

	// Public because the configuration package builds the provider's urls and its security matcher
	// from them, and it is now a different package. One definition of each path, still.
	public static final String DISCOVERY_PATH = "/.well-known/openid-configuration";
	public static final String AUTHORIZE_PATH = "/oauth2/authorize";
	public static final String TOKEN_PATH = "/oauth2/token";
	public static final String JWKS_PATH = "/oauth2/jwks";

	private final MockOidcProperties properties;
	private final MockOidcAuthorizationService authorization;
	private final MockOidcTokenService tokens;
	private final MockOidcKeyService keys;

	public MockOidcController(MockOidcProperties properties,
			MockOidcAuthorizationService authorization, MockOidcTokenService tokens,
			MockOidcKeyService keys) {
		this.properties = properties;
		this.authorization = authorization;
		this.tokens = tokens;
		this.keys = keys;
	}

	/**
	 * Discovery: everything a relying party needs to configure itself, at a well-known path.
	 *
	 * <p><b>Worth reading next to the issuer note in {@code OidcIdTokenDecoder}.</b> Here the
	 * {@code issuer} field happens to equal the base url this document was fetched from, because
	 * the mock is simple. For Azure AD B2C it does not - the metadata url carries the tenant name
	 * and the policy while the issuer carries the directory guid - which is exactly why our
	 * client configures the expected issuer as its own property instead of deriving it from a
	 * discovery url. <b>A discovery url is not a token issuer.</b>
	 */
	@GetMapping(path = DISCOVERY_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> discovery() {
		Map<String, Object> document = new LinkedHashMap<>();
		document.put("issuer", properties.issuer());
		document.put("authorization_endpoint", properties.authorizationUri());
		document.put("token_endpoint", properties.tokenUri());
		document.put("jwks_uri", properties.jwkSetUri());
		document.put("response_types_supported", List.of("code"));
		document.put("subject_types_supported", List.of("public"));
		document.put("id_token_signing_alg_values_supported", List.of("RS256"));
		document.put("grant_types_supported", List.of("authorization_code"));
		document.put("token_endpoint_auth_methods_supported", List.of("client_secret_post"));
		document.put("scopes_supported", List.of("openid", "profile", "email"));
		document.put("claims_supported",
				List.of("sub", "iss", "aud", "exp", "iat", "nonce", "email", "name"));
		return document;
	}

	/** The public half of the signing key. This is what the relying party verifies against. */
	@GetMapping(path = JWKS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> jwks() {
		return keys.jwkSet();
	}

	/**
	 * The sign-in page.
	 *
	 * <p>Validates the authorization request <em>before</em> rendering anything: a request that
	 * could never produce a code should not show a user a password box first. The parameters are
	 * then carried in the form so the POST can re-validate them rather than trust that this GET
	 * happened.
	 *
	 * <p>Deliberately does <b>not</b> return a token, or a code, or anything else. It returns a
	 * page with a button, because that is what a real provider does and because a flow that
	 * skipped this step would not be testing the flow.
	 */
	@GetMapping(path = AUTHORIZE_PATH, produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> authorizePage(
			@RequestParam(name = "client_id", required = false) String clientId,
			@RequestParam(name = "redirect_uri", required = false) String redirectUri,
			@RequestParam(name = "response_type", required = false) String responseType,
			@RequestParam(name = "scope", required = false) String scope,
			@RequestParam(name = "state", required = false) String state,
			@RequestParam(name = "nonce", required = false) String nonce) {

		log.info("Mock OIDC authorization request received for client {}", clientId);
		authorization.validateAuthorizationRequest(clientId, redirectUri, responseType, scope);

		return ResponseEntity.ok()
				.contentType(MediaType.TEXT_HTML)
				.body(signInPage(clientId, redirectUri, responseType, scope, state, nonce));
	}

	/**
	 * "Login &amp; Authorize": authenticate the pre-populated user, issue a code, redirect back.
	 *
	 * <p><b>The code goes in the url; the tokens never do.</b> That is the authorization-code
	 * flow's entire reason for existing - a browser redirect is visible in history, in referrer
	 * headers and in any proxy along the way, so what travels there must be a single-use value
	 * that is worthless without a client secret held only by the backend.
	 *
	 * <p>{@code state} is echoed back untouched, because it is the relying party's value and the
	 * provider's only job with it is to return it.
	 */
	@PostMapping(path = AUTHORIZE_PATH)
	public ResponseEntity<Void> authorize(
			@RequestParam(name = "client_id", required = false) String clientId,
			@RequestParam(name = "redirect_uri", required = false) String redirectUri,
			@RequestParam(name = "response_type", required = false) String responseType,
			@RequestParam(name = "scope", required = false) String scope,
			@RequestParam(name = "state", required = false) String state,
			@RequestParam(name = "nonce", required = false) String nonce) {

		IssuedCode issued = authorization.authorize(clientId, redirectUri, responseType, scope, nonce);

		UriComponentsBuilder location = UriComponentsBuilder.fromUriString(redirectUri)
				.queryParam("code", issued.code());
		if (state != null) {
			location.queryParam("state", state);
		}

		return ResponseEntity.status(HttpStatus.FOUND)
				.location(URI.create(location.build().encode().toUriString()))
				.build();
	}

	/** The back-channel exchange. Form-encoded in, JSON out, exactly as RFC 6749 specifies. */
	@PostMapping(path = TOKEN_PATH,
			consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> token(
			@RequestParam(name = "grant_type", required = false) String grantType,
			@RequestParam(name = "code", required = false) String code,
			@RequestParam(name = "client_id", required = false) String clientId,
			@RequestParam(name = "client_secret", required = false) String clientSecret,
			@RequestParam(name = "redirect_uri", required = false) String redirectUri) {

		return tokens.exchange(grantType, code, clientId, clientSecret, redirectUri);
	}

	/**
	 * Refusals in the OAuth 2.0 error shape, not ours.
	 *
	 * <p>{@code {"error": "...", "error_description": "..."}} is what RFC 6749 §5.2 defines and
	 * what a client library expects from a provider. Answering our own {@code {code, message,
	 * traceId}} here would make the mock behave unlike the thing it stands in for, in precisely
	 * the place a client's error handling is exercised.
	 *
	 * <p>400 rather than 401 even for {@code invalid_client}: the specification permits either,
	 * and 400 keeps a {@code WWW-Authenticate} negotiation out of a flow that has no use for one.
	 */
	@ExceptionHandler(MockOidcRequestException.class)
	public ResponseEntity<Map<String, String>> handleRefusal(MockOidcRequestException refusal) {
		return ResponseEntity.badRequest()
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("error", refusal.error(), "error_description", refusal.getMessage()));
	}

	/**
	 * The sign-in page, as one string.
	 *
	 * <p>No template engine and no static resource: this is a development fixture, and keeping it
	 * in the class that serves it means the mock is self-contained and deletable in one step.
	 *
	 * <p><b>Every interpolated value is HTML-escaped.</b> They arrive as query parameters, so
	 * they are attacker-controlled by definition, and a mock with an XSS hole in it is still an
	 * XSS hole on a developer's machine - one that would happily read the state and nonce out of
	 * the very page they are displayed in.
	 */
	private String signInPage(String clientId, String redirectUri, String responseType,
			String scope, String state, String nonce) {

		MockOidcUser user = properties.user();
		return """
				<!DOCTYPE html>
				<html lang="en">
				<head>
				  <meta charset="utf-8">
				  <title>Local Mock OIDC</title>
				  <style>
				    body { font-family: ui-monospace, monospace; background: #f4f4f5; padding: 3rem; }
				    .card { max-width: 34rem; margin: 0 auto; background: #fff; border: 1px solid #d4d4d8;
				            border-radius: 8px; padding: 2rem; }
				    h1 { font-size: 1.1rem; margin: 0 0 0.25rem; }
				    .sub { color: #71717a; font-size: 0.8rem; margin-bottom: 1.5rem; }
				    label { display: block; font-size: 0.75rem; color: #52525b; margin-top: 1rem; }
				    input[readonly] { width: 100%%; padding: 0.5rem; margin-top: 0.25rem;
				                      border: 1px solid #d4d4d8; border-radius: 4px; background: #fafafa; }
				    button { margin-top: 1.75rem; width: 100%%; padding: 0.7rem; font-size: 0.9rem;
				             background: #18181b; color: #fff; border: 0; border-radius: 4px;
				             cursor: pointer; }
				    .warn { margin-top: 1.5rem; font-size: 0.7rem; color: #b45309; }
				  </style>
				</head>
				<body>
				  <div class="card">
				    <h1>Local Mock OIDC</h1>
				    <div class="sub">%s</div>
				    <form method="post" action="%s">
				      <label>User</label>
				      <input readonly value="%s">
				      <label>Name</label>
				      <input readonly value="%s">
				      <input type="hidden" name="client_id" value="%s">
				      <input type="hidden" name="redirect_uri" value="%s">
				      <input type="hidden" name="response_type" value="%s">
				      <input type="hidden" name="scope" value="%s">
				      <input type="hidden" name="state" value="%s">
				      <input type="hidden" name="nonce" value="%s">
				      <button type="submit">Login &amp; Authorize</button>
				    </form>
				    <div class="warn">Development fixture. This is not an identity provider and
				      never runs outside a local profile.</div>
				  </div>
				</body>
				</html>
				""".formatted(
				escape(properties.issuer()),
				escape(AUTHORIZE_PATH),
				escape(user.email()),
				escape(user.name()),
				escape(clientId),
				escape(redirectUri),
				escape(responseType),
				escape(scope),
				escape(state),
				escape(nonce));
	}

	private static String escape(String value) {
		return (value != null) ? HtmlUtils.htmlEscape(value) : "";
	}
}
