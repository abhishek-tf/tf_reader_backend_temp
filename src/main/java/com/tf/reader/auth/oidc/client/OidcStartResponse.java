package com.tf.reader.auth.oidc.client;

import java.time.Instant;

import com.tf.reader.auth.model.Institution;

/**
 * Response body for {@code POST /api/v1/auth/oidc/start}.
 *
 * <p><b>Field for field {@link com.tf.reader.auth.saml.SamlStartResponse}</b>, and the contract says so explicitly
 * ("deliberately the same shape"). The app routes on the institution's sign-in method and then
 * writes <em>one</em> sign-in flow: open {@code authorizationUrl} in a browser, wait for the
 * callback. Two shapes here would mean two code paths in the client for a difference it does not
 * care about.
 *
 * <p><b>Why this is not the token envelope.</b> Same reason as SAML's. OIDC's authorization code
 * flow is a browser redirect protocol: the code is delivered to our callback by the identity
 * provider, not returned down this JSON call, so no endpoint can both start OIDC and hand back a
 * session. The token is minted at the callback, once an ID token has been validated.
 *
 * <p>{@code authTxnId} is echoed back for the client to correlate its own state. It is the value
 * that travels as the OAuth 2.0 {@code state} parameter, and it is not a credential - it proves
 * nothing on its own, and the institution it maps to is held server-side.
 */
public record OidcStartResponse(
		String authTxnId,
		String authorizationUrl,
		Institution institution,
		Instant expiresAt,
		Instant serverTime) {
}
