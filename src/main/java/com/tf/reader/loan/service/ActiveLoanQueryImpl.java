package com.tf.reader.loan.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.tf.reader.loan.api.ActiveLoanQuery;
import com.tf.reader.loan.api.ActiveLoanView;
import com.tf.reader.loan.entity.Loan;
import com.tf.reader.loan.entity.LoanStatus;
import com.tf.reader.loan.repository.LoanRepository;

/**
 * Implementation of {@link ActiveLoanQuery}.
 *
 * <p>D-006: an {@code ACTIVE} row whose {@code dueAt} has already passed is <em>not</em> live — the
 * expiry sweeper is periodic, so the column lags reality. Liveness is therefore re-derived here from
 * {@code dueAt} against the injected clock, never trusted from the stored status alone.
 */
@Service
public class ActiveLoanQueryImpl implements ActiveLoanQuery {

	private final LoanRepository loans;
	private final Clock clock;

	public ActiveLoanQueryImpl(LoanRepository loans, Clock clock) {
		this.loans = loans;
		this.clock = clock;
	}

	@Override
	public Optional<ActiveLoanView> findActive(String userId, String itemId) {
		return loans.findByUserIdAndItemIdAndStatus(userId, itemId, LoanStatus.ACTIVE)
				.filter(this::isLive)
				.map(this::toView);
	}

	/** ACTIVE and either open-ended ({@code dueAt == null}) or not yet past its due date. */
	private boolean isLive(Loan loan) {
		Instant dueAt = loan.getDueAt();
		return dueAt == null || dueAt.isAfter(clock.instant());
	}

	private ActiveLoanView toView(Loan loan) {
		return new ActiveLoanView(
				loan.getLoanId(),
				loan.getItemId(),
				loan.getLicenceModel() == null ? null : loan.getLicenceModel().name(),
				loan.isCanPersist(),
				loan.getDueAt());
	}
}
