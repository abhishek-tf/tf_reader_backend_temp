package com.tf.reader.loan.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import com.tf.reader.loan.entity.Loan;
import com.tf.reader.loan.entity.LoanStatus;

/**
 * The {@code loans} collection.
 *
 * <p><b>Never injected outside {@code loan/}.</b> Anything another capability needs about a
 * reader's loans is a published contract in {@code loan/api} (e.g. {@code ActiveLoanQuery}), not
 * this interface — a repository crossing a boundary is how two capabilities end up owning one
 * collection.
 */
public interface LoanRepository extends MongoRepository<Loan, String> {

	/**
	 * The duplicate check (invariant #2 / #3): does this reader already hold this title in the given
	 * state? Called with {@link LoanStatus#ACTIVE} <em>before any lease call</em> on create.
	 */
	Optional<Loan> findByUserIdAndItemIdAndStatus(String userId, String itemId, LoanStatus status);

	/**
	 * The expiry sweeper's work list: loans in {@code status} whose scheduled end has passed on the
	 * server clock. Open-ended loans ({@code dueAt == null}) never match, so the sweeper skips them
	 * for free (D-005).
	 */
	List<Loan> findByStatusAndDueAtLessThanEqual(LoanStatus status, Instant now);

	/** The personal library listing, scoped to one reader (the token's user). */
	Page<Loan> findByUserId(String userId, Pageable pageable);

	/** The same listing, narrowed by the optional {@code ?status=} filter. */
	Page<Loan> findByUserIdAndStatus(String userId, LoanStatus status, Pageable pageable);
}
