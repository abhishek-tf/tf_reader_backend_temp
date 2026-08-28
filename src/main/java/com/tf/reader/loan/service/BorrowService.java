package com.tf.reader.loan.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.tf.reader.catalogue.api.AccessLevel;
import com.tf.reader.catalogue.api.DenyReason;
import com.tf.reader.catalogue.api.EntitlementDecision;
import com.tf.reader.catalogue.api.EntitlementQuery;
import com.tf.reader.catalogue.api.SubjectRef;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.loan.api.LicenceCommand;
import com.tf.reader.loan.api.LicenceView;
import com.tf.reader.loan.dto.BorrowResponse;
import com.tf.reader.loan.entity.LicenceModel;
import com.tf.reader.loan.entity.Loan;
import com.tf.reader.loan.entity.LoanStatus;
import com.tf.reader.loan.repository.LoanRepository;
import com.tf.reader.reading.api.CopyLease;
import com.tf.reader.reading.api.LeaseHandle;
import org.springframework.stereotype.Service;

@Service
public class BorrowService implements LicenceCommand {

	private final LoanRepository loanRepository;
	private final EntitlementQuery entitlement;
	private final CopyLease copyLease;
	private final Clock clock;

	// Four collaborators: the repo, the two other-team ports the borrow flow calls, and the clock.
	public BorrowService(LoanRepository loanRepository, EntitlementQuery entitlement,
			CopyLease copyLease, Clock clock) {
		this.loanRepository = loanRepository;
		this.entitlement = entitlement;
		this.copyLease = copyLease;
		this.clock = clock;
	}

	/** What a borrow produced, and whether it was newly created (201) or already held (200). */
	public record BorrowResult(BorrowResponse body, boolean created) {
	}

	/**
	 * The borrow flow behind {@code POST /api/v1/loans} (D-024): entitlement (port) → duplicate check
	 * <em>before</em> any lease call (invariant #2) → ELITE lease claim → {@link #create} → release the
	 * lease if the save fails. Coexists with the read broker's create; idempotency keeps them apart.
	 */
	public BorrowResult borrow(SubjectRef subject, String itemId) {
		EntitlementDecision decision = entitlement.check(subject, itemId);
		if (!decision.entitled()) {
			throw new ApiException(mapDeny(decision.reason()), "You cannot borrow this title.");
		}

		// Duplicate check BEFORE any lease call: a re-borrow returns the held loan and claims nothing.
		Optional<Loan> existing =
				loanRepository.findByUserIdAndItemIdAndStatus(subject.userId(), itemId, LoanStatus.ACTIVE);
		if (existing.isPresent()) {
			return new BorrowResult(toBody(existing.get(), subject), false);
		}

		LeaseHandle held = null;
		if (decision.accessLevel() == AccessLevel.ENTITLED_CONCURRENT) {
			int copies = decision.copies() != null ? decision.copies() : 1;
			held = copyLease.claim(subject.institutionId(), itemId, copies)
					.orElseThrow(() -> new ApiException(ErrorCode.NO_COPIES_AVAILABLE,
							"No copies available right now."));
		}

		try {
			LicenceView view = create(subject, itemId, decision.accessLevel(), decision.loanPeriodDays(),
					held == null ? null : held.token());
			return new BorrowResult(toBody(view, decision.accessLevel(), subject), true);
		} catch (RuntimeException e) {
			if (held != null) {
				copyLease.release(held.token());   // never strand a slot
			}
			throw e;
		}
	}

	private BorrowResponse toBody(Loan loan, SubjectRef subject) {
		return new BorrowResponse(loan.getLoanId(), subject.userId(), subject.institutionId(),
				loan.getItemId(),
				loan.getLicenceModel() == null ? null : loan.getLicenceModel().name(),
				loan.getStatus().name(), loan.isCanPersist(), loan.getBorrowedAt(), loan.getDueAt(),
				clock.instant());
	}

	private BorrowResponse toBody(LicenceView view, AccessLevel accessLevel, SubjectRef subject) {
		Instant now = clock.instant();
		return new BorrowResponse(view.licenceId(), subject.userId(), subject.institutionId(),
				view.itemId(), modelName(accessLevel),
				LoanStatus.ACTIVE.name(), view.canPersist(), now, view.expiresAt(), now);
	}

	private static String modelName(AccessLevel level) {
		return switch (level) {
			case OPEN_ACCESS -> LicenceModel.OPEN_ACCESS.name();
			case ENTITLED_UNLIMITED -> LicenceModel.SUBSCRIPTION.name();
			case ENTITLED_CONCURRENT -> LicenceModel.ELITE.name();
		};
	}

	private static ErrorCode mapDeny(DenyReason reason) {
		if (reason == null) {
			return ErrorCode.NO_ENTITLEMENT;
		}
		return switch (reason) {
			case NO_ENTITLEMENT -> ErrorCode.NO_ENTITLEMENT;
			case ENTITLEMENT_EXPIRED -> ErrorCode.ENTITLEMENT_EXPIRED;
			case ENTITLEMENT_SUSPENDED -> ErrorCode.ENTITLEMENT_SUSPENDED;
			case INSTITUTION_INACTIVE -> ErrorCode.INSTITUTION_INACTIVE;
			case CONTENT_NOT_READY -> ErrorCode.CONTENT_NOT_READY;
			case NOT_FOUND -> ErrorCode.NOT_FOUND;
		};
	}

	@Override
	public LicenceView create(SubjectRef subject, String itemId, AccessLevel accessLevel, int loanPeriodDays, String leaseId) {
		String userId = subject != null ? subject.userId() : null;
		String institutionId = subject != null ? subject.institutionId() : null;

		if (userId != null) {
			var existing = loanRepository.findByUserIdAndItemIdAndStatus(userId, itemId, LoanStatus.ACTIVE);
			if (existing.isPresent()) {
				Loan loan = existing.get();
				return new LicenceView(
						loan.getLoanId(),
						loan.getUserId(),
						loan.getItemId(),
						accessLevel,
						loan.isCanPersist(),
						loan.getDueAt(),
						loan.getLeaseId()
				);
			}
		}

		LicenceModel model = switch (accessLevel) {
			case OPEN_ACCESS -> LicenceModel.OPEN_ACCESS;
			case ENTITLED_UNLIMITED -> LicenceModel.SUBSCRIPTION;
			case ENTITLED_CONCURRENT -> LicenceModel.ELITE;
		};

		boolean canPersist = (accessLevel != AccessLevel.ENTITLED_CONCURRENT);
		Instant now = clock.instant();
		Instant dueAt = (accessLevel == AccessLevel.ENTITLED_CONCURRENT && loanPeriodDays > 0)
				? now.plus(java.time.Duration.ofDays(loanPeriodDays))
				: null;

		Loan loan = Loan.builder()
				.loanId("loan_" + UUID.randomUUID().toString().substring(0, 8))
				.itemId(itemId)
				.userId(userId)
				.institutionId(institutionId)
				.licenceModel(model)
				.status(LoanStatus.ACTIVE)
				.canPersist(canPersist)
				.leaseId(leaseId)
				.borrowedAt(now)
				.dueAt(dueAt)
				.build();

		try {
			loan = loanRepository.save(loan);
		} catch (Exception e) {
			if (userId != null) {
				var existing = loanRepository.findByUserIdAndItemIdAndStatus(userId, itemId, LoanStatus.ACTIVE);
				if (existing.isPresent()) {
					Loan existingLoan = existing.get();
					return new LicenceView(
							existingLoan.getLoanId(),
							existingLoan.getUserId(),
							existingLoan.getItemId(),
							accessLevel,
							existingLoan.isCanPersist(),
							existingLoan.getDueAt(),
							existingLoan.getLeaseId()
					);
				}
			}
			throw e;
		}

		return new LicenceView(
				loan.getLoanId(),
				loan.getUserId(),
				loan.getItemId(),
				accessLevel,
				loan.isCanPersist(),
				loan.getDueAt(),
				loan.getLeaseId()
		);
	}
}

