package com.tf.reader.hold.api;

import java.time.Instant;

/**
 * Read model of one live, unexpired offer — a promoted reader holding a lease slot with no
 * loan yet. The reconciler's rebuild read: an offer has no {@code loan}, so rebuilding Redis
 * from loans alone would delete every offer's slot mid-promotion.
 *
 * @param leaseToken {@code CopyLease}'s opaque handle token for this offer's slot
 * @param expiresAt  absolute deadline; already-lapsed offers are never returned
 */
public record LiveOfferView(
		String scope,
		String itemId,
		String leaseToken,
		Instant expiresAt) {
}
