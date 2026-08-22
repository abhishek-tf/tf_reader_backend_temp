package com.tf.reader.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.tf.reader.hold.api.HoldPromotion;
import com.tf.reader.loan.entity.LicenceModel;
import com.tf.reader.loan.entity.Loan;
import com.tf.reader.loan.entity.LoanStatus;
import com.tf.reader.loan.repository.LoanRepository;
import com.tf.reader.loan.service.ExpirySweeper;
import com.tf.reader.reading.api.CopyLease;

/**
 * Termination without a user — the expiry sweep (D-023). Same close-then-release-then-promote order
 * as return, driven from Mongo (the query already excludes open-ended {@code dueAt == null} loans),
 * and best-effort per item so one bad row never strands every later slot.
 */
class ExpirySweeperTest {

	private static final Instant NOW = Instant.parse("2026-08-22T10:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private final LoanRepository loans = mock(LoanRepository.class);
	private final CopyLease copyLease = mock(CopyLease.class);
	private final HoldPromotion holdPromotion = mock(HoldPromotion.class);
	private final ExpirySweeper sweeper = new ExpirySweeper(loans, copyLease, holdPromotion, CLOCK);

	@Test
	void expiresAPastDueEliteLoanThenReleasesTheLeaseThenPromotes() {
		Loan elite = pastDueElite("loan_1", "item_1", "lease_1");
		when(loans.findByStatusAndDueAtLessThanEqual(LoanStatus.ACTIVE, NOW)).thenReturn(List.of(elite));
		when(loans.save(any(Loan.class))).thenAnswer(i -> i.getArgument(0));

		sweeper.sweep();

		assertThat(elite.getStatus()).isEqualTo(LoanStatus.EXPIRED);
		assertThat(elite.getExpiredAt()).isEqualTo(NOW);
		InOrder order = inOrder(loans, copyLease, holdPromotion);
		order.verify(loans).save(any(Loan.class));
		order.verify(copyLease).release("lease_1");
		order.verify(holdPromotion).promote("item_1");
	}

	@Test
	void expiringASubscriptionReleasesNoLease() {
		Loan sub = pastDueSubscription("loan_2", "item_2");
		when(loans.findByStatusAndDueAtLessThanEqual(LoanStatus.ACTIVE, NOW)).thenReturn(List.of(sub));
		when(loans.save(any(Loan.class))).thenAnswer(i -> i.getArgument(0));

		sweeper.sweep();

		verify(copyLease, never()).release(anyString());
		verify(holdPromotion).promote("item_2");
	}

	@Test
	void oneFailingItemDoesNotAbortTheBatch() {
		Loan bad = pastDueElite("loan_bad", "item_bad", "lease_bad");
		Loan good = pastDueElite("loan_good", "item_good", "lease_good");
		when(loans.findByStatusAndDueAtLessThanEqual(LoanStatus.ACTIVE, NOW)).thenReturn(List.of(bad, good));
		// First save blows up (the bad row); the second succeeds — the batch must continue.
		when(loans.save(any(Loan.class)))
				.thenThrow(new RuntimeException("mongo hiccup"))
				.thenAnswer(i -> i.getArgument(0));

		sweeper.sweep();

		verify(holdPromotion).promote("item_good");
		verify(holdPromotion, never()).promote(eq("item_bad"));
	}

	private Loan pastDueElite(String loanId, String itemId, String leaseId) {
		return Loan.builder()
				.loanId(loanId).userId("user_1").itemId(itemId).institutionId("inst_1")
				.licenceModel(LicenceModel.ELITE).status(LoanStatus.ACTIVE).canPersist(false)
				.leaseId(leaseId).borrowedAt(NOW.minus(Duration.ofDays(20)))
				.dueAt(NOW.minus(Duration.ofDays(1))).build();
	}

	private Loan pastDueSubscription(String loanId, String itemId) {
		return Loan.builder()
				.loanId(loanId).userId("user_1").itemId(itemId).institutionId("inst_1")
				.licenceModel(LicenceModel.SUBSCRIPTION).status(LoanStatus.ACTIVE).canPersist(true)
				.borrowedAt(NOW.minus(Duration.ofDays(20)))
				.dueAt(NOW.minus(Duration.ofDays(1))).build();
	}
}
