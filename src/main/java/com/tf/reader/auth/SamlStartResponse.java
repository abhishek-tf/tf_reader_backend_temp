package com.tf.reader.auth;

import java.time.Instant;

import com.tf.reader.auth.model.Institution;

/**
 * Response body for {@code POST /api/v1/auth/saml/start}.
 *
 * <p><b>Why this is not the contract's token envelope.</b> SAML is a browser redirect protocol:
 * the assertion is delivered to our ACS by the IdP, not returned down this JSON call, so no
 * endpoint can both start SAML and hand back a session in one response. This endpoint therefore
 * returns the URL the client must open, which is the same shape every mobile OAuth/SSO flow
 * uses. The token envelope in the API Reference belongs to the end of the flow, at the ACS,
 * once TokenService exists.
 *
 * <p>{@code authTxnId} is echoed back for the client to correlate its own state. It is not a
 * credential and proves nothing on its own - the institution it maps to is held server-side.
 */
public record SamlStartResponse(
		String authTxnId,
		String authorizationUrl,
		Institution institution,
		Instant expiresAt,
		Instant serverTime) {
}
