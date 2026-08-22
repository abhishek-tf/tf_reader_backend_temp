package com.tf.reader.library.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response body carrying a page of change entries.
 *
 * @param changes    oldest first, so applying them in order converges
 * @param nextCursor opaque. The client sends it back as {@code since} and does not parse it
 * @param hasMore    rather than a total: the feed is a stream, and counting it would mean reading
 *                   all of it to answer a question the client never asks
 * @param serverTime the one clock on this screen. Every countdown the app renders is a difference
 *                   against this, never against the device clock
 */
public record ChangesResponse(
		List<ChangeEntryView> changes,
		String nextCursor,
		boolean hasMore,
		Instant serverTime) {
}
