package com.tf.reader.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import com.tf.reader.catalogue.api.AccessLevel;
import com.tf.reader.catalogue.api.EntitlementQuery;
import com.tf.reader.catalogue.api.SubjectRef;
import com.tf.reader.loan.api.LicenceView;
import com.tf.reader.loan.entity.LicenceModel;
import com.tf.reader.loan.entity.Loan;
import com.tf.reader.loan.entity.LoanStatus;
import com.tf.reader.loan.repository.LoanRepository;
import com.tf.reader.loan.service.BorrowService;
import com.tf.reader.reading.api.CopyLease;

/**
 * The create-flow paths of the roadmap's Days 6–7, at the service level.
 *
 * <p>In the adopted design (D-020) the entitlement (→403) and copy-lease (→409) decisions live in
 * the read broker, whose refusals and lease-release-on-failure are covered by
 * {@code ReadBrokerServiceTest}. What remains here — and what these tests pin — is the persistence
 * core: the AccessLevel→LicenceModel translation, the three tiers' persist/expiry shape, and the
 * idempotency the partial-unique index demands (return the existing or the race winner, never a
 * second row).
 */
class BorrowServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final SubjectRef SUBJECT = new SubjectRef("user_1", "inst_1");

	private final LoanRepository loans = mock(LoanRepository.class);
	// create() (the LicenceCommand port) uses neither the entitlement nor the lease port — those
	// are the borrow-flow's collaborators (see BorrowFlowTest). Mocks satisfy the constructor.
	private final BorrowService service = new BorrowService(
			loans, mock(EntitlementQuery.class), mock(CopyLease.class), CLOCK);

	@Test
	void subscriptionCreatesAnUnlimitedLicenceThatCanPersist() {
		noExistingLoan();
		savesTheGivenLoan();

		LicenceView view = service.create(SUBJECT, "item_1", AccessLevel.ENTITLED_UNLIMITED, 0, null);

		assertThat(view.canPersist()).isTrue();
		assertThat(view.expiresAt()).isNull();
		assertThat(view.leaseId()).isNull();
		assertThat(savedLoan().getLicenceModel()).isEqualTo(LicenceModel.SUBSCRIPTION);
	}

	@Test
	void openAccessCreatesAPersistableOpenEndedLicence() {
		noExistingLoan();
		savesTheGivenLoan();

		LicenceView view = service.create(SUBJECT, "item_1", AccessLevel.OPEN_ACCESS, 0, null);

		assertThat(view.canPersist()).isTrue();
		assertThat(view.expiresAt()).isNull();
		assertThat(savedLoan().getLicenceModel()).isEqualTo(LicenceModel.OPEN_ACCESS);
	}

	@Test
	void eliteCreatesACopyLimitedLicenceWithLeaseAndDueDate() {
		noExistingLoan();
		savesTheGivenLoan();

		LicenceView view = service.create(SUBJECT, "item_1", AccessLevel.ENTITLED_CONCURRENT, 14, "lease_abc");

		assertThat(view.canPersist()).isFalse();
		assertThat(view.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
		assertThat(view.leaseId()).isEqualTo("lease_abc");
		Loan saved = savedLoan();
		assertThat(saved.getLicenceModel()).isEqualTo(LicenceModel.ELITE);
		assertThat(saved.getLeaseId()).isEqualTo("lease_abc");
	}

	@Test
	void anExistingActiveLicenceIsReturnedWithoutASecondSave() {
		Loan existing = Loan.builder()
				.loanId("loan_existing").userId("user_1").itemId("item_1")
				.licenceModel(LicenceModel.SUBSCRIPTION).status(LoanStatus.ACTIVE)
				.canPersist(true).borrowedAt(NOW.minus(Duration.ofDays(1))).build();
		when(loans.findByUserIdAndItemIdAndStatus("user_1", "item_1", LoanStatus.ACTIVE))
				.thenReturn(Optional.of(existing));

		LicenceView view = service.create(SUBJECT, "item_1", AccessLevel.ENTITLED_UNLIMITED, 0, null);

		assertThat(view.licenceId()).isEqualTo("loan_existing");
		verify(loans, never()).save(any());
	}

	@Test
	void aDuplicateKeyRaceReReadsAndReturnsTheWinner() {
		Loan winner = Loan.builder()
				.loanId("loan_winner").userId("user_1").itemId("item_1")
				.licenceModel(LicenceModel.SUBSCRIPTION).status(LoanStatus.ACTIVE).canPersist(true).build();
		when(loans.findByUserIdAndItemIdAndStatus("user_1", "item_1", LoanStatus.ACTIVE))
				.thenReturn(Optional.empty())      // the pre-save duplicate check
				.thenReturn(Optional.of(winner));  // the post-race re-read
		when(loans.save(any(Loan.class))).thenThrow(new DuplicateKeyException("E11000 duplicate key"));

		LicenceView view = service.create(SUBJECT, "item_1", AccessLevel.ENTITLED_UNLIMITED, 0, null);

		assertThat(view.licenceId()).isEqualTo("loan_winner");
	}

	@Test
	void aSaveFailureWithNoWinnerPropagates() {
		noExistingLoan();
		when(loans.save(any(Loan.class))).thenThrow(new RuntimeException("mongo down"));

		assertThatThrownBy(() -> service.create(SUBJECT, "item_1", AccessLevel.ENTITLED_UNLIMITED, 0, null))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("mongo down");
	}

	private void noExistingLoan() {
		when(loans.findByUserIdAndItemIdAndStatus(any(), any(), eq(LoanStatus.ACTIVE)))
				.thenReturn(Optional.empty());
	}

	private void savesTheGivenLoan() {
		when(loans.save(any(Loan.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	private Loan savedLoan() {
		ArgumentCaptor<Loan> captor = ArgumentCaptor.forClass(Loan.class);
		verify(loans).save(captor.capture());
		return captor.getValue();
	}
}
