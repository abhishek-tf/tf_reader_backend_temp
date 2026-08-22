package com.tf.reader.loan.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.tf.reader.loan.entity.LicenceModel;
import com.tf.reader.loan.entity.Loan;
import com.tf.reader.loan.entity.LoanStatus;

/**
 * Prototype in-memory loan fixtures.
 *
 * <p>Seeded around the API Reference worked example (user {@code user_9c2} at {@code inst_7f3}), so
 * fixtures agree across teams and a demo / Postman has loans to read without going through the
 * borrow flow first. One loan per {@link LicenceModel} plus a returned one, so every {@code ?status=}
 * filter and each tier is visible.
 *
 * <p>This is a seam, not the store. The real {@code loans} collection is the Mongo-backed
 * {@link LoanRepository}; this class never replaces it — it only holds deterministic sample data,
 * exactly as {@code auth.repository.MockUserRepository} does for users.
 */
@Component
public class MockLoanRepository {

	private static final List<Loan> LOANS = List.of(
			Loan.builder()
					.loanId("loan_7c1").userId("user_9c2").itemId("item_42").institutionId("inst_7f3")
					.licenceModel(LicenceModel.ELITE).status(LoanStatus.ACTIVE).canPersist(false)
					.leaseId("lease_7c1")
					.borrowedAt(Instant.parse("2026-08-13T10:00:00Z"))
					.dueAt(Instant.parse("2026-08-27T10:00:00Z"))
					.build(),
			Loan.builder()
					.loanId("loan_ab6").userId("user_9c2").itemId("item_ab6").institutionId("inst_7f3")
					.licenceModel(LicenceModel.SUBSCRIPTION).status(LoanStatus.ACTIVE).canPersist(true)
					.borrowedAt(Instant.parse("2026-08-10T09:00:00Z"))
					.build(),
			Loan.builder()
					.loanId("loan_oa1").userId("user_9c2").itemId("item_oa1").institutionId("inst_7f3")
					.licenceModel(LicenceModel.OPEN_ACCESS).status(LoanStatus.ACTIVE).canPersist(true)
					.borrowedAt(Instant.parse("2026-08-01T08:00:00Z"))
					.build(),
			Loan.builder()
					.loanId("loan_ret").userId("user_9c2").itemId("item_ret").institutionId("inst_7f3")
					.licenceModel(LicenceModel.SUBSCRIPTION).status(LoanStatus.RETURNED).canPersist(true)
					.borrowedAt(Instant.parse("2026-07-20T08:00:00Z"))
					.returnedAt(Instant.parse("2026-07-28T08:00:00Z"))
					.build());

	/** The active loan this reader holds for this title, or empty. Mirrors the ACTIVE duplicate check. */
	public Optional<Loan> findActive(String userId, String itemId) {
		return LOANS.stream()
				.filter(loan -> loan.getStatus() == LoanStatus.ACTIVE)
				.filter(loan -> loan.getUserId().equals(userId) && loan.getItemId().equals(itemId))
				.findFirst();
	}

	/** Every loan this reader holds, any status — the personal-library listing's fixture source. */
	public List<Loan> findByUser(String userId) {
		return LOANS.stream()
				.filter(loan -> loan.getUserId().equals(userId))
				.toList();
	}

	public List<Loan> all() {
		return LOANS;
	}
}
