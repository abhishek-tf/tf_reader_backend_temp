package com.tf.reader.loan.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.tf.reader.loan.entity.LicenceModel;
import com.tf.reader.loan.entity.LoanStatus;

/**
 * The prototype in-memory loan fixtures. Other developers (and Postman) build against these, so
 * determinism matters as much as correctness — same fixtures, same ids, every run.
 */
class MockLoanRepositoryTest {

	private final MockLoanRepository loans = new MockLoanRepository();

	@Test
	void seedsLoansForTheWorkedExampleUser() {
		assertThat(loans.findByUser("user_9c2")).isNotEmpty();
	}

	@Test
	void findsTheActiveEliteLoanForAUserAndItem() {
		assertThat(loans.findActive("user_9c2", "item_42"))
				.get()
				.satisfies(loan -> {
					assertThat(loan.getLoanId()).isEqualTo("loan_7c1");
					assertThat(loan.getLicenceModel()).isEqualTo(LicenceModel.ELITE);
					assertThat(loan.getStatus()).isEqualTo(LoanStatus.ACTIVE);
					assertThat(loan.isCanPersist()).isFalse();
				});
	}

	@Test
	void scopesLoansToTheirOwner() {
		assertThat(loans.findByUser("user_9c2"))
				.isNotEmpty()
				.allSatisfy(loan -> assertThat(loan.getUserId()).isEqualTo("user_9c2"));
		assertThat(loans.findByUser("nobody")).isEmpty();
	}

	@Test
	void reportsNoActiveLoanForAnItemTheUserDoesNotHold() {
		assertThat(loans.findActive("user_9c2", "item_not_held")).isEmpty();
	}

	@Test
	void aReturnedLoanIsNotActive() {
		// A RETURNED fixture exists in the set, but findActive only surfaces live loans.
		assertThat(loans.all()).anySatisfy(loan -> assertThat(loan.getStatus()).isEqualTo(LoanStatus.RETURNED));
		assertThat(loans.findActive("user_9c2", "item_ret")).isEmpty();
	}
}
