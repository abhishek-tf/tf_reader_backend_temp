package com.tf.reader.loan.dto;

import java.time.Instant;

/**
 * The result of returning a title.
 *
 * <p>Carries the closed loan's ending and {@code serverTime} (invariant #4). {@code promoted} is not
 * reported yet — {@code HoldPromotion.promote} is {@code void}, so whether a waiter was actually
 * handed the freed copy is unobservable here (D-022; flagged for the hold owner).
 */
public record ReturnResponse(
		String loanId,
		String itemId,
		String status,
		Instant returnedAt,
		Instant serverTime) {
}
