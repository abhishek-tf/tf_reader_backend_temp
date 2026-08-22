package com.tf.reader.auth.saml.transaction;

import java.time.Instant;

/**
 * One in-flight institutional sign-in.
 *
 * <p>This is the record that survives the round trip to the IdP. The {@code id} is the only
 * part that ever leaves the server, and it is opaque: it carries no institution, no user and
 * no other meaning, so nothing can be learned or forged from it.
 */
public record AuthTransaction(String id, String institutionId, Instant createdAt, Instant expiresAt) {

	public boolean hasExpiredAt(Instant now) {
		return !now.isBefore(expiresAt);
	}
}
