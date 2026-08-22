package com.tf.reader.loan.dto;

import java.time.Instant;

/**
 * The result of a borrow ({@code POST /api/v1/loans}).
 *
 * <p>Carries the loan the reader now holds plus {@code serverTime} (invariant #4). The HTTP status
 * tells new-vs-existing (201 created / 200 already held); this body is the same shape either way.
 */
public record BorrowResponse(
		String loanId,
		String itemId,
		String licenceModel,
		String status,
		boolean canPersist,
		Instant borrowedAt,
		Instant dueAt,
		Instant serverTime) {
}
