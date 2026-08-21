package com.tf.reader.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.tf.reader.loan.api.ActiveLoanView;
import com.tf.reader.loan.entity.LicenceModel;
import com.tf.reader.loan.entity.Loan;
import com.tf.reader.loan.entity.LoanStatus;
import com.tf.reader.loan.repository.LoanRepository;
import com.tf.reader.loan.service.ActiveLoanQueryImpl;

/**
 * The active-licence check (Day 8), and the one rule that makes it correct: D-006 — liveness is
 * re-derived from {@code dueAt} against the server clock, never read off the raw {@code status}.
 *
 * <p>A row can sit at {@code status = ACTIVE} after its {@code dueAt} has passed, because the expiry
 * sweeper is periodic, not instant. Trusting the column would report a lapsed licence as live. So a
 * loan is active iff it is {@code ACTIVE} AND ({@code dueAt} is null OR still in the future).
 */
class ActiveLoanQueryTest {

	private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private final LoanRepository loans = mock(LoanRepository.class);
	private final ActiveLoanQueryImpl query = new ActiveLoanQueryImpl(loans, CLOCK);

	@Test
	void reportsActiveWhenTheLoanIsStillWithinItsDueDate() {
		stubActiveRow(loanDueAt(NOW.plus(Duration.ofDays(3))));

		Optional<ActiveLoanView> result = query.findActive("user_1", "item_1");

		assertThat(result).isPresent();
		assertThat(result.get().loanId()).isEqualTo("loan_1");
	}

	@Test
	void treatsAnOpenEndedLoanAsAlwaysActive() {
		stubActiveRow(loanDueAt(null));

		assertThat(query.findActive("user_1", "item_1")).isPresent();
	}

	@Test
	void reDerivesInactiveWhenTheDueDateHasAlreadyPassed() {
		// The row still says ACTIVE — the sweeper has not run yet. D-006: we do not trust it.
		stubActiveRow(loanDueAt(NOW.minus(Duration.ofSeconds(1))));

		assertThat(query.findActive("user_1", "item_1")).isEmpty();
	}

	@Test
	void reportsInactiveWhenThereIsNoActiveRowAtAll() {
		when(loans.findByUserIdAndItemIdAndStatus("user_1", "item_1", LoanStatus.ACTIVE))
				.thenReturn(Optional.empty());

		assertThat(query.findActive("user_1", "item_1")).isEmpty();
	}

	private void stubActiveRow(Loan loan) {
		when(loans.findByUserIdAndItemIdAndStatus("user_1", "item_1", LoanStatus.ACTIVE))
				.thenReturn(Optional.of(loan));
	}

	private Loan loanDueAt(Instant dueAt) {
		return Loan.builder()
				.loanId("loan_1").userId("user_1").itemId("item_1")
				.licenceModel(LicenceModel.ELITE).status(LoanStatus.ACTIVE)
				.canPersist(false).borrowedAt(NOW.minus(Duration.ofDays(1))).dueAt(dueAt)
				.build();
	}
}
