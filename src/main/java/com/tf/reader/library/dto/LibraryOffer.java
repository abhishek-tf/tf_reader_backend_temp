package com.tf.reader.library.dto;

import java.time.Instant;

/**
 * A copy being held for this reader right now, and the moment it stops being held.
 *
 * <p>{@code expiresAt} is absolute and server-issued. The app renders the difference against the
 * response's {@code serverTime} and never against the device clock: a device an hour fast shows a
 * countdown that has already finished, and the reader abandons a copy that is still theirs.
 *
 * <p>Two fields rather than three. {@code hold.api.OfferView} also carries {@code offeredAt}, which
 * the screen has no use for — the deadline is what the reader is racing, not the start.
 */
public record LibraryOffer(
		String offerId,
		Instant expiresAt) {
}
