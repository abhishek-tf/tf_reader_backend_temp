package com.tf.reader.loan.api;

import java.time.Instant;

/**
 * Read model of a currently held, still-live loan.
 *
 * <p>Returned only when the loan is genuinely active — see {@link ActiveLoanQuery}, which
 * re-derives liveness from {@code dueAt} rather than trusting the stored status (D-006).
 *
 * @param dueAt         scheduled end; {@code null} for an open-ended (Subscription / Open-Access) loan.
 * @param institutionId the lease scope; {@code null} for a personal, non-institutional loan.
 * @param leaseId       the held {@code CopyLease} token; {@code null} unless the loan is ELITE.
 * @param borrowedAt    when the loan was created, on the server clock (D-026 — for library screen countdowns).
 * @param status        lifecycle state as a string; always {@code "ACTIVE"} from this port due to D-006,
 *                      but carried explicitly so Module E does not hard-code a constant (D-026).
 */
public record ActiveLoanView(
		String loanId,
		String itemId,
		String licenceModel,
		boolean canPersist,
		Instant dueAt,
		String institutionId,
		String leaseId,
		Instant borrowedAt,
		String status) {
}
