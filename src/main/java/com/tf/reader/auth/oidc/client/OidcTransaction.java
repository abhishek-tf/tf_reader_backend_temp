package com.tf.reader.auth.oidc.client;

import java.time.Instant;

/**
 * One in-flight OIDC sign-in.
 *
 * <p>The OIDC counterpart of {@link com.tf.reader.auth.saml.transaction.AuthTransaction}, and separate
 * from it for one reason: <b>the nonce</b>. A SAML transaction needs to carry an institution
 * across a redirect; an OIDC one needs to carry an institution, a state and a nonce, and the
 * nonce has no SAML analogue at all - it is bound into the ID token by the provider and checked
 * against what we sent. Widening the SAML record to hold a field only OIDC uses would put a null
 * in every SAML sign-in and invite somebody to start reading it.
 *
 * <p><b>{@code id} and {@code state} are different values on purpose.</b> {@code state} is the
 * correlator on the wire - it goes to the provider and comes back through the browser, and it is
 * what the callback is looked up by. {@code id} is the handle we hand the client so it can
 * correlate its own UI, and it is what the API contract calls {@code authTxnId}. Keeping them
 * distinct means the value a client has seen is not the value that redeems a sign-in.
 *
 * <p>Neither carries any meaning: both are opaque random strings, so nothing can be learned or
 * forged from either. The institution stays here, on the server, and is never sent anywhere.
 */
public record OidcTransaction(
		String id,
		String institutionId,
		String state,
		String nonce,
		Instant createdAt,
		Instant expiresAt) {

	public boolean hasExpiredAt(Instant now) {
		return !now.isBefore(expiresAt);
	}
}
