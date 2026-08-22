package com.tf.reader.loan.api;

import java.time.Instant;

/**
 * Read model of a currently held, still-live loan.
 *
 * <p>Returned only when the loan is genuinely active — see {@link ActiveLoanQuery}, which
 * re-derives liveness from {@code dueAt} rather than trusting the stored status (D-006).
 *
 * @param dueAt scheduled end; {@code null} for an open-ended (Subscription / Open-Access) loan.
 */
public record ActiveLoanView(
		String loanId,
		String itemId,
		String licenceModel,
		boolean canPersist,
		Instant dueAt) {
}
