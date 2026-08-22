package com.tf.reader.auth.oidc.client;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tf.reader.auth.oidc.client.OidcAuthenticationService.OidcLoginResult;

/**
 * The two OIDC endpoints: starting a sign-in, and receiving the provider's callback.
 *
 * <p>HTTP only. Every decision lives in {@link OidcAuthenticationService}, which is why that
 * class can be tested without a servlet and this one has almost nothing in it. The same split
 * the SAML leg uses, where the handlers do HTTP and the service does the deciding.
 *
 * <p><b>Both routes are public, and both have to be.</b> {@code /start} is how a caller obtains
 * a credential, so it cannot require one; {@code /callback} is entered by a browser redirect
 * from the provider, which carries no bearer token and never will. Neither trusts anything the
 * caller sends: {@code /start} takes an institution id and resolves it against our repository,
 * and {@code /callback} takes a code and a state, both of which are meaningless unless they
 * match a sign-in this backend started.
 *
 * <p><b>No session is created by either.</b> State and nonce live in
 * {@link OidcTransactionStore}, server-side, so the whole OIDC flow runs on the stateless API
 * chain - there is no JSESSIONID for anybody to reuse as a second credential, which is the bug
 * {@code StatelessApiTest} exists to keep fixed on the SAML side.
 */
@RestController
@RequestMapping("/api/v1/auth/oidc")
public class OidcController {

	private static final org.slf4j.Logger log =
			org.slf4j.LoggerFactory.getLogger(OidcController.class);

	private final OidcAuthenticationService authentication;

	public OidcController(OidcAuthenticationService authentication) {
		this.authentication = authentication;
	}

	/**
	 * Begins institutional sign-in over OIDC.
	 *
	 * <p>The OIDC counterpart of {@code POST /auth/saml/start}, and deliberately the same
	 * request and response shape, so the app writes one sign-in flow and chooses a url rather
	 * than writing two. Records which institution was chosen, server-side, under an opaque
	 * transaction; the {@code state} that travels to the provider is what brings it back.
	 */
	@PostMapping("/start")
	public OidcStartResponse start(@Valid @RequestBody OidcStartRequest request) {
		return authentication.start(request.institutionId());
	}

	/**
	 * Where the identity provider sends the browser back.
	 *
	 * <p>{@code state} is required rather than optional: a callback without one cannot be matched
	 * to a sign-in, and Spring rejecting it as a missing parameter says exactly that. {@code code}
	 * is optional at this level so that a provider-side failure - the user cancelling, say, which
	 * arrives as {@code ?error=access_denied} with no code - is refused by our own service with
	 * our own error body, rather than by the framework with its own.
	 *
	 * <p><b>Nothing from the query string is trusted beyond these two values</b>, and neither is
	 * believed on its own: the code is only ever redeemed at the provider, and the state only
	 * ever looked up in our store.
	 */
	@GetMapping("/callback")
	public OidcLoginResult callback(
			@RequestParam(name = "code", required = false) String code,
			@RequestParam(name = "state") String state,
			@RequestParam(name = "error", required = false) String error) {

		// The provider's own error code is short and fixed by the specification, so it is safe to
		// log; its error_description is not read at all, because that is where correlation ids and
		// configuration detail live. Neither is ever returned to the client.
		log.info("OIDC callback received{}", (error != null) ? " carrying provider error: " + error : "");

		return authentication.complete(code, state);
	}
}
