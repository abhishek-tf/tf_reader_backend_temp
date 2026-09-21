package com.tf.reader.loan.service;

import java.time.Clock;
import java.time.Duration;
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
import com.tf.reader.library.api.ChangeLog;
import com.tf.reader.library.api.ChangeReason;
import com.tf.reader.library.api.ChangeRecord;
import com.tf.reader.loan.api.LicenceCommand;
import com.tf.reader.loan.api.LicenceView;
import com.tf.reader.loan.dto.BorrowResponse;
import com.tf.reader.loan.entity.LicenceModel;
import com.tf.reader.loan.entity.Loan;
import com.tf.reader.loan.entity.LoanStatus;
import com.tf.reader.loan.repository.LoanRepository;
import com.tf.reader.reading.api.CopyLease;
import com.tf.reader.reading.api.LeaseHandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class BorrowService implements LicenceCommand {

	private final LoanRepository loanRepository;
	private final EntitlementQuery entitlement;
	private final CopyLease copyLease;
	private final ChangeLog changeLog;
	private final Clock clock;
	// Null in production. Set loan.debug.loan-duration (e.g. PT5M) in application-local.yml
	// to shorten every new loan for manual testing of expiry and queue promotion flows.
	private final Duration debugLoanDuration;

	// Five collaborators: the repo, the two other-team ports the borrow flow calls, the change-feed
	// port (create() is the single loan-birth chokepoint, so LOAN_CREATED belongs here — D-029), and
	// the clock. Above the 3-param guideline, but each is a distinct capability this job needs.
	public BorrowService(LoanRepository loanRepository, EntitlementQuery entitlement,
			CopyLease copyLease, ChangeLog changeLog, Clock clock,
			@Value("${loan.debug.loan-duration:#{null}}") Duration debugLoanDuration) {
		this.loanRepository = loanRepository;
		this.entitlement = entitlement;
		this.copyLease = copyLease;
		this.changeLog = changeLog;
		this.clock = clock;
		this.debugLoanDuration = debugLoanDuration;
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
		log.info("borrow: request itemId={} userId={} institutionId={}", itemId, subject.userId(),
				subject.institutionId());
		EntitlementDecision decision = entitlement.check(subject, itemId);
		if (!decision.entitled()) {
			log.info("borrow: denied itemId={} userId={} reason={}", itemId, subject.userId(), decision.reason());
			throw new ApiException(mapDeny(decision.reason()), "You cannot borrow this title.");
		}

		// Duplicate check BEFORE any lease call: a re-borrow returns the held loan and claims nothing.
		Optional<Loan> existing =
				loanRepository.findByUserIdAndItemIdAndStatus(subject.userId(), itemId, LoanStatus.ACTIVE);
		if (existing.isPresent()) {
			log.info("borrow: already held loanId={} itemId={} userId={}", existing.get().getLoanId(), itemId,
					subject.userId());
			return new BorrowResult(toBody(existing.get(), subject), false);
		}

		LeaseHandle held = null;
		if (decision.accessLevel() == AccessLevel.ENTITLED_CONCURRENT) {
			int copies = decision.copies() != null ? decision.copies() : 1;
			held = copyLease.claim(subject.institutionId(), itemId, copies)
					.orElseThrow(() -> {
						log.info("borrow: no copies available itemId={} userId={}", itemId, subject.userId());
						return new ApiException(ErrorCode.NO_COPIES_AVAILABLE, "No copies available right now.");
					});
		}

		try {
			LicenceView view = create(subject, itemId, decision.accessLevel(), decision.loanPeriodDays(),
					held == null ? null : held.token());
			log.info("borrow: granted loanId={} itemId={} userId={} accessLevel={}", view.licenceId(), itemId,
					subject.userId(), decision.accessLevel());
			return new BorrowResult(toBody(view, decision.accessLevel(), subject), true);
		} catch (RuntimeException e) {
			if (held != null) {
				log.warn("borrow: failed after claiming a copy itemId={} userId={}, releasing it", itemId,
						subject.userId(), e);
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
		// dueAt = borrowedAt + loanPeriodDays for anything but OPEN_ACCESS (which never expires),
		// per the Loan contract. loanPeriodDays <= 0 means the entitlement is unlimited, so the loan
		// stays open-ended (null) — a Subscription can be either windowed or open-ended (D-030).
		// debugLoanDuration overrides the days when set (application-local.yml only, never production).
		Instant dueAt = (accessLevel != AccessLevel.OPEN_ACCESS && (loanPeriodDays > 0 || debugLoanDuration != null))
				? now.plus(debugLoanDuration != null ? debugLoanDuration : Duration.ofDays(loanPeriodDays))
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

		// Only here — a genuinely new loan was saved. The two idempotent branches above return an
		// existing loan without saving, so they must NOT record: the port is not idempotent, and a
		// re-borrow (or Read tap) of a held title would otherwise write a phantom LOAN_CREATED (D-029).
		// After the state write, per the ChangeLog contract; it never throws, so no try/catch.
		if (userId != null) {
			changeLog.record(ChangeRecord.forLoan(userId, ChangeReason.LOAN_CREATED, itemId,
					loan.getLoanId(), now));
		}
		log.info("loan: created loanId={} itemId={} userId={} accessLevel={} dueAt={}", loan.getLoanId(), itemId,
				userId, accessLevel, dueAt);

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

	@Override
	public boolean hasExpiredLoan(String userId, String itemId) {
		return loanRepository.findByUserIdAndItemIdAndStatus(userId, itemId, LoanStatus.EXPIRED).isPresent();
	}

	@Override
	public boolean hasActiveLoan(String userId, String itemId) {
		return loanRepository.findByUserIdAndItemIdAndStatus(userId, itemId, LoanStatus.ACTIVE).isPresent();
	}

	@Override
	public String activeLoanLeaseId(String userId, String itemId) {
		return loanRepository.findByUserIdAndItemIdAndStatus(userId, itemId, LoanStatus.ACTIVE)
				.map(Loan::getLeaseId)
				.orElse(null);
	}
}

