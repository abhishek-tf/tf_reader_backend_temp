package com.tf.reader.reading.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import com.tf.reader.catalogue.api.AccessLevel;
import com.tf.reader.catalogue.api.DenyReason;
import com.tf.reader.catalogue.api.EntitlementDecision;
import com.tf.reader.catalogue.api.EntitlementQuery;
import com.tf.reader.catalogue.api.SubjectRef;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.content.api.ContentAccessGrant;
import com.tf.reader.content.api.ContentGrant;
import com.tf.reader.content.api.ContentGrantRequest;
import com.tf.reader.content.api.LoanProof;
import com.tf.reader.hold.api.HoldView;
import com.tf.reader.hold.api.QueueJoin;
import com.tf.reader.library.api.ChangeLog;
import com.tf.reader.library.api.ChangeRecord;
import com.tf.reader.loan.api.LicenceCommand;
import com.tf.reader.loan.api.LicenceView;
import com.tf.reader.reading.api.CopyLease;
import com.tf.reader.reading.api.LeaseHandle;
import com.tf.reader.reading.dto.ReadingSessionRequest;
import com.tf.reader.reading.dto.ReadingSessionResponse;

/**
 * The read broker orchestrator for reading and downloading.
 *
 * <p>Executes all 9 steps of the read and download flow, stopping at the first refusal.
 */
@Slf4j
@Service
public class ReadBrokerService {

	private static final Duration SESSION_TTL = Duration.ofMinutes(5);

	private final EntitlementQuery entitlements;
	private final ContentAccessGrant content;
	private final LicenceCommand licences;
	private final CopyLease lease;
	private final QueueJoin queue;
	private final ReconcilerService reconciler;
	private final DeviceCapService devices;
	private final RightsService rights;
	private final ChangeLog changeLog;
	private final Clock clock;

	public ReadBrokerService(
			EntitlementQuery entitlements,
			ContentAccessGrant content,
			LicenceCommand licences,
			CopyLease lease,
			QueueJoin queue,
			ReconcilerService reconciler,
			DeviceCapService devices,
			RightsService rights,
			ChangeLog changeLog,
			Clock clock) {
		this.entitlements = entitlements;
		this.content = content;
		this.licences = licences;
		this.lease = lease;
		this.queue = queue;
		this.reconciler = reconciler;
		this.devices = devices;
		this.rights = rights;
		this.changeLog = changeLog;
		this.clock = clock;
	}

	public ReadingSessionResponse open(SubjectRef subject, ReadingSessionRequest request) {
		String userId = subject != null ? subject.userId() : null;
		String institutionId = subject != null ? subject.institutionId() : null;
		log.info("read-broker: open itemId={} userId={} institutionId={} intent={} format={}",
				request.itemId(), userId, institutionId, request.intent(), request.format());

		// ── Step 1: Validate device key format ──
		byte[] deviceKey = decodeDeviceKey(request.devicePublicKey());

		// ── Step 2: Entitlement check ──
		EntitlementDecision decision = entitlements.check(subject, request.itemId());
		log.info("read-broker: entitlement itemId={} userId={} entitled={} accessLevel={} reason={} copies={}",
				request.itemId(), userId, decision.entitled(), decision.accessLevel(), decision.reason(),
				decision.copies());
		if (!decision.entitled()) {
			// For downloadable tiers, the change log is the only channel that reaches a device
			// which already has the title on disk. Write ENTITLEMENT_REVOKED before refusing,
			// so an offline reader eventually learns the access is gone. Best-effort: ChangeLog
			// never throws, so a feed failure never converts a clean 403 into a 500.
			// Elite is online-only — the re-check here IS the enforcement; no feed needed.
			if (isRevocationReason(decision.reason()) && isDownloadableTier(decision.accessLevel())) {
				changeLog.record(ChangeRecord.forRevocation(
						subject.userId(),
						request.itemId(),
						"unknown", // loanId is not available at refusal time — see note below
						clock.instant()));
				// NOTE: ideally we would pass the existing loanId so the feed entry is richer,
				// but LicenceCommand only exposes create(), not findByUserAndItem(). Using
				// "unknown" is acceptable per the ChangeLog contract — the feed consumer uses
				// the (userId, itemId, reason) triple to act, not the loanId. Track as task-29b
				// to wire in the loanId once Shashank publishes a read-only query.
			}
			log.info("read-broker: denied itemId={} userId={} reason={}", request.itemId(), userId,
					decision.reason());
			throw new ApiException(mapDenyReason(decision.reason()), "You do not have access to this title.");
		}

		// ── Step 3: Rights check ──
		rights.check(decision.accessLevel(), request.intent(), request.format());

		// ── Step 4: Claim copy (ELITE / ENTITLED_CONCURRENT only) ──
		boolean copyLimited = decision.accessLevel() == AccessLevel.ENTITLED_CONCURRENT;
		LeaseHandle held = null;

		if (copyLimited) {
			String scope = subject != null ? subject.institutionId() : null;

			// If this reader already holds an active loan for this title, try to EXTEND the
			// lease that loan was originally granted with, rather than unconditionally claiming
			// a brand-new one. This is THE fix for a severe double-count: without it, `create()`
			// below is idempotent and correctly returns the SAME existing loan on every open —
			// but by then this method had already claimed an entirely separate, second lease on
			// every single call, including the very first open right after borrowing. One reader
			// borrowing, then opening, a 2-copy title consumed BOTH copies by themselves, and
			// their own next open found none free. See queue audit, 2026-09-20.
			Optional<LeaseHandle> claimed = Optional.empty();
			if (userId != null) {
				String existingLeaseId = licences.activeLoanLeaseId(userId, request.itemId());
				if (existingLeaseId != null) {
					LeaseHandle existingHandle = new LeaseHandle(existingLeaseId, scope, request.itemId(), clock.instant());
					if (lease.extend(existingHandle, clock.instant().plus(SESSION_TTL))) {
						log.info("read-broker: reusing existing lease itemId={} userId={}", request.itemId(), userId);
						claimed = Optional.of(existingHandle);
					}
					// Extend can fail honestly — the old lease already expired in Redis because
					// nothing re-checked access in the last 30+ seconds. That slot is genuinely
					// free again; falling through to a fresh claim() below reclaims it under a
					// new token, exactly as it would for a first-time open.
				}
			}
			if (claimed.isEmpty()) {
				claimed = lease.claim(scope, request.itemId(), decision.copies());
			}
			if (claimed.isEmpty()) {
				// A reader who already holds this title's loan has no business joining ITS OWN
				// wait queue — QueueService.join() refuses that outright (VALIDATION_FAILED, so
				// the same title is never shown as both granted and waiting at once — see queue
				// audit, 2026-09-20). But that refusal is meant for the direct POST /api/v1/holds
				// endpoint, where it is the honest, actionable answer. Reached from here instead,
				// it is confusing: this reader is not asking to queue, they are asking to READ
				// something they already have — the lease claim just failed because Redis's
				// ephemeral concurrent-reading state (rebuilt from Mongo after every restart, or
				// simply still catching up) hasn't caught up with reality yet. Checked and handled
				// BEFORE calling queuedResponse() so this reader gets NO_COPIES_AVAILABLE (a code
				// the client already has a real message for) instead of an unmapped
				// VALIDATION_FAILED it can only show as a generic failure.
				if (userId != null && licences.hasActiveLoan(userId, request.itemId())) {
					log.info("read-broker: no copy free but reader already holds this loan itemId={} userId={}",
							request.itemId(), userId);
					throw new ApiException(ErrorCode.NO_COPIES_AVAILABLE,
							"This title's reading slots are all busy right now — try again in a moment.");
				}
				log.info("read-broker: no copy free itemId={} userId={} institutionId={}, joining queue",
						request.itemId(), userId, institutionId);
				// No free copy — join the wait queue in this same call instead of making
				// the client turn around and call POST /api/v1/holds itself. No licence,
				// no content grant: the reader has nothing to read yet, only a place in line.
				// Deliberately BEFORE the device cap check below: joining a wait list spends no
				// device slot and no copy, so a reader already at their cap must still be able
				// to queue for a title that is fully checked out. Checking the cap first (the
				// original order) meant a capped reader got DEVICE_LIMIT_REACHED for a full
				// title and could never join its queue at all — see queue audit, 2026-09-20.
				return queuedResponse(subject, request.itemId());
			}
			held = claimed.get();
			// Never the lease token itself in a log line — it is a bearer handle for this copy
			// slot, the same category of thing as the tokens STYLE forbids logging.
			log.info("read-broker: claimed copy itemId={} userId={}", request.itemId(), userId);
		}

		// ── Step 5: Device cap check (ELITE only) ──
		// Open access and subscription access are not copy/device limited. Only an Elite
		// entitlement consumes a concurrent-reading device slot, and only once a copy is
		// actually in hand — see the note on Step 4 above for why this runs after the claim.
		if (copyLimited && subject != null && subject.userId() != null) {
			boolean admitted = devices.admit(subject.userId(), deviceKey);
			log.info("read-broker: device cap itemId={} userId={} admitted={}", request.itemId(), userId, admitted);
			if (!admitted) {
				lease.release(held);
				throw new ApiException(ErrorCode.DEVICE_LIMIT_REACHED, "This account is already reading on the maximum number of devices.");
			}
		}

		try {
			// ── Step 5b: Reject silently-expired ELITE seats ──
			// create() is idempotent while a loan is ACTIVE, but falls through and mints a fresh
			// loan when the sweeper has already flipped it to EXPIRED. For ELITE that silently
			// re-issues a seat the system just reclaimed, defeating the copy limit entirely.
			// A STREAM re-check is not the same as a fresh borrow: a user whose loan expired
			// must go through POST /api/v1/loans explicitly to get a new seat.
			if (copyLimited && userId != null && licences.hasExpiredLoan(userId, request.itemId())) {
				throw new ApiException(ErrorCode.NO_ENTITLEMENT, "Your loan for this title has expired.");
			}

			// ── Step 6: Create licence ──
			LicenceView licence = licences.create(
					subject,
					request.itemId(),
					decision.accessLevel(),
					decision.loanPeriodDays(),
					held == null ? null : held.token()
			);
			log.info("read-broker: licence created licenceId={} itemId={} userId={} accessLevel={} canPersist={}",
					licence.licenceId(), request.itemId(), userId, decision.accessLevel(), licence.canPersist());

			// ── Step 6b: Reject a past-due loan before it reaches wokay ──
			// create() returns an existing ACTIVE loan even when its dueAt has already passed but
			// the sweeper hasn't run yet. Passing that stale dueAt to content.grant() produces
			// NO_ACTIVE_LOAN, which the client treats as fail-open (network-hiccup bucket).
			// Detecting it here and throwing NO_ENTITLEMENT (fail-closed) is the right boundary:
			// the loan genuinely expired — this is a confirmed denial, not an uncertain one.
			if (licence.expiresAt() != null && licence.expiresAt().isBefore(clock.instant())) {
				throw new ApiException(ErrorCode.NO_ENTITLEMENT, "Your loan for this title has expired.");
			}

			// ── Step 7: Fetch content grant ──
			ContentGrant grant = content.grant(new ContentGrantRequest(
					request.itemId(),
					request.format(),
					request.intent(),
					deviceKey,
					subject,
					new LoanProof(licence.licenceId(), licence.expiresAt()),
					request.wantSearchIndex(),
					request.partNumber()
			));
			// Never the grant's own payload — it carries signed URLs and encryption info, the
			// same bearer-capability category as the tokens STYLE forbids logging.
			log.info("read-broker: content grant issued licenceId={} itemId={} userId={} format={} intent={}",
					licence.licenceId(), request.itemId(), userId, request.format(), request.intent());

			// ── Step 8: Extend claim to THIS READING SESSION's own lifetime, not the loan's due
			// date. A copy lease means "actively reading right now" — the loan's dueAt is null
			// for ELITE (no subscription-style due date at all, confirmed by NPE here against a
			// real re-fetched ELITE loan, 2026-08-25) and, for SUBSCRIPTION, days-to-weeks away,
			// which would hold a copy hostage for the whole loan period on a single open instead
			// of releasing it when the session ends — defeating the copy limit's own purpose.
			Instant now = clock.instant();
			Instant sessionExpiresAt = now.plus(SESSION_TTL);
			if (copyLimited && !lease.extend(held, sessionExpiresAt)) {
				log.warn("read-broker: extend failed itemId={} userId={}, reconciling", request.itemId(), userId);
				reconciler.reconcile(request.itemId());
				// ACCEPTED GAP: the response is returned even if reconcile() does not restore
				// the lease. Design intent is "recover, never rollback" — the reader has the
				// licence and already holds the title; refusing now would be worse than a copy
				// count that is temporarily one short. The 30-second claim TTL self-heals the
				// slot without any action from the caller. Tested by
				// ReadBrokerServiceTest.returnsSessionEvenWhenExtendAndReconcileBothFail.
			}

			// ── Step 9: Forward payload unchanged ──
			String sessionId = "sess_" + UUID.randomUUID().toString().substring(0, 8);
			log.info("read-broker: session opened sessionId={} licenceId={} itemId={} userId={} accessLevel={} expiresAt={}",
					sessionId, licence.licenceId(), request.itemId(), userId, decision.accessLevel(), sessionExpiresAt);
			return new ReadingSessionResponse(
					sessionId,
					licence.licenceId(),
					request.itemId(),
					decision.accessLevel().name(),
					licenceModelOf(decision.accessLevel()),
					licence.canPersist(),
					null,
					grant.content(),
					grant.index(),
					grant.encryption(),
					sessionExpiresAt,
					now
			);
			// holdCreatedAt is null here: a direct read never creates a hold.

		} catch (RuntimeException failure) {
			if (held != null) {
				log.warn("read-broker: session failed after claiming a copy itemId={} userId={}, releasing it",
						request.itemId(), userId, failure);
				lease.release(held);
			}
			throw failure;
		}
	}

	/**
	 * Every copy of an ELITE title is taken. Instead of refusing with {@code NO_COPIES_AVAILABLE}
	 * and telling the client to call {@code POST /api/v1/holds} itself, join the queue right here
	 * and hand back a session response carrying only {@code holdCreatedAt} — there is no licence
	 * and nothing to read yet, only a place in line. Queue position/length/ETA live on
	 * {@code GET /api/v1/holds}, not here.
	 */
	private ReadingSessionResponse queuedResponse(SubjectRef subject, String itemId) {
		QueueJoin.JoinResult joined = queue.join(subject.userId(), subject.institutionId(), itemId);
		HoldView hold = joined.hold();
		log.info("read-broker: queued holdId={} itemId={} userId={} created={}", hold.holdId(), itemId,
				subject.userId(), joined.created());

		return new ReadingSessionResponse(
				"sess_" + UUID.randomUUID().toString().substring(0, 8),
				null,
				itemId,
				AccessLevel.ENTITLED_CONCURRENT.name(),
				licenceModelOf(AccessLevel.ENTITLED_CONCURRENT),
				false,
				hold.placedAt(),
				null,
				null,
				null,
				null,
				clock.instant()
		);
	}

	private byte[] decodeDeviceKey(String base64) {
		try {
			byte[] raw = Base64.getDecoder().decode(base64);
			if (raw.length == 0) {
				throw new IllegalArgumentException("empty");
			}
			return raw;
		} catch (IllegalArgumentException e) {
			throw new ApiException(ErrorCode.INVALID_DEVICE_PUBLIC_KEY, "devicePublicKey must be valid base64 of the raw public key bytes.");
		}
	}

	private static ErrorCode mapDenyReason(DenyReason reason) {
		if (reason == null) return ErrorCode.NO_ENTITLEMENT;
		return switch (reason) {
			case NO_ENTITLEMENT -> ErrorCode.NO_ENTITLEMENT;
			case ENTITLEMENT_EXPIRED -> ErrorCode.ENTITLEMENT_EXPIRED;
			case ENTITLEMENT_SUSPENDED -> ErrorCode.ENTITLEMENT_SUSPENDED;
			case INSTITUTION_INACTIVE -> ErrorCode.INSTITUTION_INACTIVE;
			case CONTENT_NOT_READY -> ErrorCode.CONTENT_NOT_READY;
			case NOT_FOUND -> ErrorCode.NOT_FOUND;
		};
	}

	/**
	 * Whether this deny reason represents an entitlement that was actively withdrawn
	 * rather than a title that was never accessible or is simply not ready.
	 *
	 * <p>Only withdrawn entitlements need a change-log entry: a reader whose institution's
	 * subscription lapsed has a downloaded title they can no longer open, and the feed is
	 * the only way to tell them. A reader who never had access, or whose title is not yet
	 * available, has no downloaded copy to notify about.
	 */
	private static boolean isRevocationReason(DenyReason reason) {
		if (reason == null) return false;
		return switch (reason) {
			case ENTITLEMENT_EXPIRED, ENTITLEMENT_SUSPENDED, INSTITUTION_INACTIVE -> true;
			case NO_ENTITLEMENT, CONTENT_NOT_READY, NOT_FOUND -> false;
		};
	}

	/**
	 * Whether this tier produces a title that can be downloaded to a device.
	 *
	 * <p>ELITE (ENTITLED_CONCURRENT) is online-only — the re-check at step 2 IS the
	 * enforcement for an offline reader, because they can never have a copy on disk.
	 * The change log entry is therefore only needed for tiers that permit downloading.
	 */
	private static boolean isDownloadableTier(AccessLevel level) {
		if (level == null) return false;
		return level == AccessLevel.OPEN_ACCESS || level == AccessLevel.ENTITLED_UNLIMITED;
	}

	private static String licenceModelOf(AccessLevel level) {
		if (level == null) return "SUBSCRIPTION";
		return switch (level) {
			case OPEN_ACCESS -> "OPEN_ACCESS";
			case ENTITLED_UNLIMITED -> "SUBSCRIPTION";
			case ENTITLED_CONCURRENT -> "ELITE";
		};
	}
}
