package com.tf.reader.loan.dto;

import java.time.Instant;

import com.tf.reader.loan.entity.Loan;

/**
 * One loan as it appears in the personal library listing.
 *
 * <p>Enums are rendered as their names so the wire shape does not leak internal types. Both endings
 * are carried separately — {@code returnedAt} (given back / revoked) and {@code expiredAt} (clock
 * ran out) — because they are different facts (D-005), and both are {@code null} while the loan
 * is live.
 */
public record LoanResponse(
		String loanId,
		String itemId,
		String licenceModel,
		String status,
		boolean canPersist,
		Instant borrowedAt,
		Instant dueAt,
		Instant returnedAt,
		Instant expiredAt) {

	public static LoanResponse from(Loan loan) {
		return new LoanResponse(
				loan.getLoanId(),
				loan.getItemId(),
				loan.getLicenceModel() == null ? null : loan.getLicenceModel().name(),
				loan.getStatus() == null ? null : loan.getStatus().name(),
				loan.isCanPersist(),
				loan.getBorrowedAt(),
				loan.getDueAt(),
				loan.getReturnedAt(),
				loan.getExpiredAt());
	}
}
