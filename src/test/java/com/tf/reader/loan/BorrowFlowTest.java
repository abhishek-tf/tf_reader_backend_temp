package com.tf.reader.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

import com.tf.reader.catalogue.api.AccessLevel;
import com.tf.reader.catalogue.api.DenyReason;
import com.tf.reader.catalogue.api.EntitlementDecision;
import com.tf.reader.catalogue.api.EntitlementQuery;
import com.tf.reader.catalogue.api.SubjectRef;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.loan.entity.LicenceModel;
import com.tf.reader.loan.entity.Loan;
import com.tf.reader.loan.entity.LoanStatus;
import com.tf.reader.loan.repository.LoanRepository;
import com.tf.reader.loan.service.BorrowService;
import com.tf.reader.reading.api.CopyLease;
import com.tf.reader.reading.api.LeaseHandle;

/**
 * The borrow flow behind {@code POST /api/v1/loans} (D-024). Orchestration only — entitlement (port)
 * → duplicate check BEFORE any lease call (invariant #2) → ELITE lease claim → create → release the
 * lease if the save fails. The persistence itself is {@code BorrowServiceTest}'s job.
 */
class BorrowFlowTest {

	private static final Instant NOW = Instant.parse("2026-08-22T10:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final SubjectRef SUBJECT = new SubjectRef("user_1", "inst_1");

	private final LoanRepository loans = mock(LoanRepository.class);
	private final EntitlementQuery entitlement = mock(EntitlementQuery.class);
	private final CopyLease copyLease = mock(CopyLease.class);
	private final BorrowService service = new BorrowService(loans, entitlement, copyLease, CLOCK);

	@Test
	void subscriptionBorrowCreatesAnUnlimitedLoanWithNoLease() {
		when(entitlement.check(SUBJECT, "item_1")).thenReturn(entitled(AccessLevel.ENTITLED_UNLIMITED, null, 0));
		noExistingLoan();
		savesTheGivenLoan();

		BorrowService.BorrowResult result = service.borrow(SUBJECT, "item_1");

		assertThat(result.created()).isTrue();
		assertThat(result.body().licenceModel()).isEqualTo("SUBSCRIPTION");
		assertThat(result.body().canPersist()).isTrue();
		assertThat(result.body().serverTime()).isEqualTo(NOW);
		verify(copyLease, never()).claim(anyString(), anyString(), anyInt());
	}

	@Test
	void eliteBorrowClaimsACopyThenCreates() {
		when(entitlement.check(SUBJECT, "item_1")).thenReturn(entitled(AccessLevel.ENTITLED_CONCURRENT, 2, 14));
		noExistingLoan();
		savesTheGivenLoan();
		when(copyLease.claim("inst_1", "item_1", 2))
				.thenReturn(Optional.of(new LeaseHandle("lease_1", "inst_1", "item_1", NOW.plus(Duration.ofSeconds(30)))));

		BorrowService.BorrowResult result = service.borrow(SUBJECT, "item_1");

		assertThat(result.created()).isTrue();
		assertThat(result.body().licenceModel()).isEqualTo("ELITE");
		assertThat(result.body().canPersist()).isFalse();
		verify(copyLease).claim("inst_1", "item_1", 2);
	}

	@Test
	void anExistingActiveLoanReturnsItWithoutClaimingOrCreating() {
		when(entitlement.check(SUBJECT, "item_1")).thenReturn(entitled(AccessLevel.ENTITLED_CONCURRENT, 2, 14));
		Loan existing = Loan.builder()
				.loanId("loan_existing").userId("user_1").itemId("item_1").institutionId("inst_1")
				.licenceModel(LicenceModel.ELITE).status(LoanStatus.ACTIVE).canPersist(false)
				.leaseId("lease_old").borrowedAt(NOW.minus(Duration.ofDays(1)))
				.dueAt(NOW.plus(Duration.ofDays(13))).build();
		when(loans.findByUserIdAndItemIdAndStatus("user_1", "item_1", LoanStatus.ACTIVE))
				.thenReturn(Optional.of(existing));

		BorrowService.BorrowResult result = service.borrow(SUBJECT, "item_1");

		assertThat(result.created()).isFalse();
		assertThat(result.body().loanId()).isEqualTo("loan_existing");
		verify(copyLease, never()).claim(anyString(), anyString(), anyInt()); // dup check before lease
		verify(loans, never()).save(any());
	}

	@Test
	void notEntitledMapsTheDenyReasonToItsErrorCode() {
		when(entitlement.check(SUBJECT, "item_1")).thenReturn(denied(DenyReason.ENTITLEMENT_EXPIRED));

		assertThatThrownBy(() -> service.borrow(SUBJECT, "item_1"))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ENTITLEMENT_EXPIRED));

		verify(copyLease, never()).claim(anyString(), anyString(), anyInt());
		verify(loans, never()).save(any());
	}

	@Test
	void eliteWithNoFreeCopyThrows409AndCreatesNothing() {
		when(entitlement.check(SUBJECT, "item_1")).thenReturn(entitled(AccessLevel.ENTITLED_CONCURRENT, 2, 14));
		noExistingLoan();
		when(copyLease.claim("inst_1", "item_1", 2)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.borrow(SUBJECT, "item_1"))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.getCode()).isEqualTo(ErrorCode.NO_COPIES_AVAILABLE));

		verify(loans, never()).save(any());
	}

	@Test
	void releasesTheClaimedLeaseIfTheSaveFails() {
		when(entitlement.check(SUBJECT, "item_1")).thenReturn(entitled(AccessLevel.ENTITLED_CONCURRENT, 2, 14));
		noExistingLoan();
		when(copyLease.claim("inst_1", "item_1", 2))
				.thenReturn(Optional.of(new LeaseHandle("lease_1", "inst_1", "item_1", NOW.plus(Duration.ofSeconds(30)))));
		when(loans.save(any(Loan.class))).thenThrow(new RuntimeException("mongo down"));

		assertThatThrownBy(() -> service.borrow(SUBJECT, "item_1")).isInstanceOf(RuntimeException.class);

		verify(copyLease).release("lease_1"); // never strand a slot
	}

	private void noExistingLoan() {
		when(loans.findByUserIdAndItemIdAndStatus(any(), any(), eq(LoanStatus.ACTIVE)))
				.thenReturn(Optional.empty());
	}

	private void savesTheGivenLoan() {
		when(loans.save(any(Loan.class))).thenAnswer(i -> i.getArgument(0));
	}

	private EntitlementDecision entitled(AccessLevel level, Integer copies, int loanPeriodDays) {
		return new EntitlementDecision(true, level, "ent_1", copies, loanPeriodDays, null, null);
	}

	private EntitlementDecision denied(DenyReason reason) {
		return new EntitlementDecision(false, null, null, null, 0, null, reason);
	}
}
