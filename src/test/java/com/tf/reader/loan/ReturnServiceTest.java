package com.tf.reader.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.hold.api.HoldPromotion;
import com.tf.reader.loan.dto.ReturnResponse;
import com.tf.reader.loan.entity.LicenceModel;
import com.tf.reader.loan.entity.Loan;
import com.tf.reader.loan.entity.LoanStatus;
import com.tf.reader.loan.repository.LoanRepository;
import com.tf.reader.loan.service.ReturnService;
import com.tf.reader.reading.api.CopyLease;

/**
 * Termination — returning a title (D-022). Pins the write order that keeps a copy from being
 * lent twice (close the loan, then release the copy, then promote), and the three refusals
 * three teams render (foreign loan, already-closed, unknown).
 */
class ReturnServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-22T10:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private final LoanRepository loans = mock(LoanRepository.class);
	private final CopyLease copyLease = mock(CopyLease.class);
	private final HoldPromotion holdPromotion = mock(HoldPromotion.class);
	private final ReturnService service = new ReturnService(loans, copyLease, holdPromotion, CLOCK);

	@Test
	void closesAnActiveEliteLoanThenReleasesTheLeaseThenPromotes() {
		Loan loan = elite("loan_1", "user_1", "item_1", "lease_1");
		when(loans.findById("loan_1")).thenReturn(Optional.of(loan));
		when(loans.save(any(Loan.class))).thenAnswer(i -> i.getArgument(0));

		ReturnResponse result = service.returnLoan("user_1", "loan_1");

		assertThat(result.status()).isEqualTo("RETURNED");
		assertThat(result.returnedAt()).isEqualTo(NOW);
		assertThat(result.serverTime()).isEqualTo(NOW);

		// Write order (invariant #1): save the closed loan → release the copy → promote.
		InOrder order = inOrder(loans, copyLease, holdPromotion);
		order.verify(loans).save(any(Loan.class));
		order.verify(copyLease).release("lease_1");
		order.verify(holdPromotion).promote("item_1");
	}

	@Test
	void aSubscriptionReturnReleasesNoLeaseButStillPromotes() {
		Loan loan = subscription("loan_2", "user_1", "item_2"); // no leaseId
		when(loans.findById("loan_2")).thenReturn(Optional.of(loan));
		when(loans.save(any(Loan.class))).thenAnswer(i -> i.getArgument(0));

		service.returnLoan("user_1", "loan_2");

		verify(copyLease, never()).release(anyString());
		verify(holdPromotion).promote("item_2");
	}

	@Test
	void refusesAForeignLoanWith403AndWritesNothing() {
		when(loans.findById("loan_3")).thenReturn(Optional.of(elite("loan_3", "other_user", "item_3", "lease_3")));

		assertThatThrownBy(() -> service.returnLoan("user_1", "loan_3"))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.getCode()).isEqualTo(ErrorCode.FORBIDDEN_SCOPE));

		verify(loans, never()).save(any());
		verify(copyLease, never()).release(anyString());
		verify(holdPromotion, never()).promote(anyString());
	}

	@Test
	void refusesAnAlreadyClosedLoanWith409() {
		Loan closed = elite("loan_4", "user_1", "item_4", "lease_4");
		closed.setStatus(LoanStatus.RETURNED);
		when(loans.findById("loan_4")).thenReturn(Optional.of(closed));

		assertThatThrownBy(() -> service.returnLoan("user_1", "loan_4"))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.getCode()).isEqualTo(ErrorCode.LOAN_NOT_ACTIVE));

		verify(loans, never()).save(any());
		verify(copyLease, never()).release(anyString());
	}

	@Test
	void refusesAnUnknownLoanWith404() {
		when(loans.findById("nope")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.returnLoan("user_1", "nope"))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.getCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	private Loan elite(String loanId, String userId, String itemId, String leaseId) {
		return Loan.builder()
				.loanId(loanId).userId(userId).itemId(itemId).institutionId("inst_1")
				.licenceModel(LicenceModel.ELITE).status(LoanStatus.ACTIVE).canPersist(false)
				.leaseId(leaseId).borrowedAt(NOW.minus(Duration.ofDays(1)))
				.dueAt(NOW.plus(Duration.ofDays(13))).build();
	}

	private Loan subscription(String loanId, String userId, String itemId) {
		return Loan.builder()
				.loanId(loanId).userId(userId).itemId(itemId).institutionId("inst_1")
				.licenceModel(LicenceModel.SUBSCRIPTION).status(LoanStatus.ACTIVE).canPersist(true)
				.borrowedAt(NOW.minus(Duration.ofDays(1))).build();
	}
}
