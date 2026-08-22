package com.tf.reader.library.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A hold as the library screen needs it.
 *
 * <p>{@code position} and {@code queueLength} are computed by the hold module on read, never stored
 * — a stored position is wrong the moment anybody ahead of it cancels. This record must not cache
 * them either.
 *
 * <p>{@code status} is a string for the same reason as {@link LibraryLoan}: {@code hold.api} models
 * it as a string too, so there is nothing to convert at the seam.
 *
 * @param estimatedWaitDays a guess, and labelled one on screen. Derived from the loan period and the
 *                          copy count, and knows nothing about early returns. Omitted once the hold
 *                          is OFFERED, because then there is a real deadline instead
 * @param offer             present only while status is OFFERED
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LibraryHold(
		String holdId,
		String itemId,
		String status,
		int position,
		int queueLength,
		Integer estimatedWaitDays,
		Instant placedAt,
		LibraryOffer offer) {
}
