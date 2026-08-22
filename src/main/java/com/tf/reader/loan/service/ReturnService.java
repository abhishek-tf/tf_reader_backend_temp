package com.tf.reader.loan.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;

import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.hold.api.HoldPromotion;
import com.tf.reader.loan.dto.ReturnResponse;
import com.tf.reader.loan.entity.Loan;
import com.tf.reader.loan.entity.LoanStatus;
import com.tf.reader.loan.repository.LoanRepository;
import com.tf.reader.reading.api.CopyLease;

/**
 * Termination — closing a loan on the user's return (D-022).
 *
 * <p>The write order is the correctness (invariant #1): mark the loan {@code RETURNED} <em>first</em>,
 * then release the copy, then promote. Reverse it and there is a window where the slot is free while
 * the loan still reads active — one copy lent twice.
 *
 * <p>The lease and promotion are other modules' ports ({@code reading.CopyLease},
 * {@code hold.HoldPromotion}); we only call them.
 */
@Service
public class ReturnService {

	private final LoanRepository loans;
	private final CopyLease copyLease;
	private final HoldPromotion holdPromotion;
	private final Clock clock;

	// Four collaborators (repo + two ports + clock) — above the 3-param guideline, but each is a
	// distinct capability this one job genuinely needs; splitting the class would scatter the order.
	public ReturnService(LoanRepository loans, CopyLease copyLease, HoldPromotion holdPromotion,
			Clock clock) {
		this.loans = loans;
		this.copyLease = copyLease;
		this.holdPromotion = holdPromotion;
		this.clock = clock;
	}

	/**
	 * @throws ApiException 404 if no such loan, 403 if it is not the caller's, 409 if it is already
	 *         closed (which makes a double-tapped return a safe no-op without an idempotency store).
	 */
	public ReturnResponse returnLoan(String userId, String loanId) {
		Loan loan = loans.findById(loanId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such loan."));
		if (!loan.getUserId().equals(userId)) {
			throw new ApiException(ErrorCode.FORBIDDEN_SCOPE, "This loan is not yours.");
		}
		if (loan.getStatus() != LoanStatus.ACTIVE) {
			throw new ApiException(ErrorCode.LOAN_NOT_ACTIVE, "This loan is already closed.");
		}

		Instant now = clock.instant();
		loan.setStatus(LoanStatus.RETURNED);
		loan.setReturnedAt(now);
		Loan closed = loans.save(loan);

		if (closed.getLeaseId() != null) {          // Elite only — release exactly once
			copyLease.release(closed.getLeaseId());
		}
		holdPromotion.promote(closed.getItemId());

		return new ReturnResponse(closed.getLoanId(), closed.getItemId(),
				closed.getStatus().name(), closed.getReturnedAt(), now);
	}
}
