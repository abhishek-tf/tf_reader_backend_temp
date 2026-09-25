package com.tf.reader.reading.service;

import com.tf.reader.catalogue.api.AccessLevel;
import com.tf.reader.catalogue.api.DenyReason;
import com.tf.reader.catalogue.api.EntitlementDecision;
import com.tf.reader.catalogue.api.EntitlementQuery;
import com.tf.reader.catalogue.api.SubjectRef;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.content.api.ContentAccessGrant;
import com.tf.reader.content.api.ContentGrant;
import com.tf.reader.content.api.Encryption;
import com.tf.reader.content.api.Format;
import com.tf.reader.content.api.IndexUrl;
import com.tf.reader.content.api.Intent;
import com.tf.reader.content.api.SignedUrl;
import com.tf.reader.hold.api.HoldView;
import com.tf.reader.hold.api.QueueJoin;
import com.tf.reader.library.api.ChangeLog;
import com.tf.reader.library.api.ChangeReason;
import com.tf.reader.loan.api.LicenceCommand;
import com.tf.reader.loan.api.LicenceView;
import com.tf.reader.reading.api.CopyLease;
import com.tf.reader.reading.api.LeaseHandle;
import com.tf.reader.reading.dto.ReadingSessionRequest;
import com.tf.reader.reading.dto.ReadingSessionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Every refusal path this endpoint can take, plus the grant pass-through.
 *
 * <p>Each refusal test asserts two things: the right code, and that nothing downstream ran.
 * That second assertion is the point of the whole design — a missing check here is invisible
 * because there is no second checkpoint behind it.
 */
class ReadBrokerServiceTest {

	private static final SubjectRef MEMBER = new SubjectRef("user_9c2", "inst_7f3");
	private static final String ITEM = "item_42";
	private static final String KEY = Base64.getEncoder().encodeToString("device-key-bytes".getBytes());
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneOffset.UTC);

	private EntitlementQuery entitlements;
	private ContentAccessGrant content;
	private LicenceCommand licences;
	private CopyLease lease;
	private QueueJoin queue;
	private ReconcilerService reconciler;
	private DeviceCapService devices;
	private RightsService rights;
	private ChangeLog changeLog;
	private ReadBrokerService broker;

	@BeforeEach
	void setUp() {
		entitlements = mock(EntitlementQuery.class);
		content = mock(ContentAccessGrant.class);
		licences = mock(LicenceCommand.class);
		lease = mock(CopyLease.class);
		queue = mock(QueueJoin.class);
		reconciler = mock(ReconcilerService.class);
		devices = mock(DeviceCapService.class);
		changeLog = mock(ChangeLog.class);
		rights = new RightsService(); // real: it is pure and cheap, no reason to fake it

		// Default: device cap always admits, unless a test says otherwise.
		when(devices.admit(anyString(), any())).thenReturn(true);

		broker = new ReadBrokerService(entitlements, content, licences, lease, queue,
				reconciler, devices, rights, changeLog, CLOCK);
	}

	private ReadingSessionRequest request(Intent intent) {
		return new ReadingSessionRequest(ITEM, Format.PDF, intent, KEY, false, null);
	}

	private EntitlementDecision entitled(AccessLevel level, Integer copies, int loanPeriodDays) {
		return new EntitlementDecision(true, level, "ent_1", copies, loanPeriodDays, null, null);
	}

	private EntitlementDecision denied(DenyReason reason) {
		return new EntitlementDecision(false, null, null, null, 0, null, reason);
	}

	/** Overload for tests that need to control which tier was revoked (downloadable vs. elite). */
	private EntitlementDecision denied(DenyReason reason, AccessLevel level) {
		return new EntitlementDecision(false, level, null, null, 0, null, reason);
	}

	// ── step 1 ──────────────────────────────────────────────────────────────

	@Test
	void anUndecodableDeviceKeyIsRejectedBeforeAnythingElseRuns() {
		ReadingSessionRequest bad = new ReadingSessionRequest(ITEM, Format.PDF, Intent.STREAM, "!!! not base64 !!!", false, null);

		assertThatThrownBy(() -> broker.open(MEMBER, bad))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo(ErrorCode.INVALID_DEVICE_PUBLIC_KEY);

		verifyNoInteractions(entitlements, devices, content, licences, lease, queue);
	}

	// ── step 2 — every DenyReason maps to its own code ───────────────────────

	@Test
	void noEntitlementRefusesBeforeAnythingDownstreamRuns() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(denied(DenyReason.NO_ENTITLEMENT));

		assertThatThrownBy(() -> broker.open(MEMBER, request(Intent.STREAM)))
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo(ErrorCode.NO_ENTITLEMENT);

		verifyNoInteractions(devices, content, licences, lease, queue);
	}

	@Test
	void entitlementExpiredIsNotCollapsedIntoNoEntitlement() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(denied(DenyReason.ENTITLEMENT_EXPIRED));

		assertThatThrownBy(() -> broker.open(MEMBER, request(Intent.STREAM)))
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo(ErrorCode.ENTITLEMENT_EXPIRED);
	}

	@Test
	void entitlementSuspendedGetsItsOwnCode() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(denied(DenyReason.ENTITLEMENT_SUSPENDED));

		assertThatThrownBy(() -> broker.open(MEMBER, request(Intent.STREAM)))
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo(ErrorCode.ENTITLEMENT_SUSPENDED);
	}

	@Test
	void institutionInactiveGetsItsOwnCode() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(denied(DenyReason.INSTITUTION_INACTIVE));

		assertThatThrownBy(() -> broker.open(MEMBER, request(Intent.STREAM)))
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo(ErrorCode.INSTITUTION_INACTIVE);
	}

	@Test
	void contentNotReadyPassesThroughUnchanged() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(denied(DenyReason.CONTENT_NOT_READY));

		assertThatThrownBy(() -> broker.open(MEMBER, request(Intent.STREAM)))
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo(ErrorCode.CONTENT_NOT_READY);
	}

	@Test
	void notFoundPassesThroughUnchanged() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(denied(DenyReason.NOT_FOUND));

		assertThatThrownBy(() -> broker.open(MEMBER, request(Intent.STREAM)))
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo(ErrorCode.NOT_FOUND);
	}

	// ── step 2 — ENTITLEMENT_REVOKED change-log writes ─────────────────────────────────────

	@Test
	void aRevokedSubscriptionWritesEntitlementRevokedToTheFeedBeforeRefusing() {
		// The feed is the only channel that reaches a device with the title already downloaded.
		when(entitlements.check(MEMBER, ITEM))
				.thenReturn(denied(DenyReason.ENTITLEMENT_EXPIRED, AccessLevel.ENTITLED_UNLIMITED));

		assertThatThrownBy(() -> broker.open(MEMBER, request(Intent.STREAM)))
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo(ErrorCode.ENTITLEMENT_EXPIRED);

		verify(changeLog).record(argThat(r ->
				r.reason() == ChangeReason.ENTITLEMENT_REVOKED
						&& r.userId().equals(MEMBER.userId())
						&& r.itemId().equals(ITEM)));
	}

	@Test
	void aRevokedEliteReadDoesNotWriteToTheFeedBecauseEliteIsOnlineOnly() {
		// Elite titles cannot be downloaded — the step-2 re-check IS the enforcement.
		// No feed entry is needed because there is no on-device copy to invalidate.
		when(entitlements.check(MEMBER, ITEM))
				.thenReturn(denied(DenyReason.ENTITLEMENT_SUSPENDED, AccessLevel.ENTITLED_CONCURRENT));

		assertThatThrownBy(() -> broker.open(MEMBER, request(Intent.STREAM)))
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo(ErrorCode.ENTITLEMENT_SUSPENDED);

		verify(changeLog, never()).record(any());
	}

	@Test
	void aNoEntitlementRefusalDoesNotWriteToTheFeedBecauseThereIsNoPriorDownloadedCopy() {
		// NO_ENTITLEMENT means the reader never had access — they have no downloaded copy
		// to invalidate, so writing to the feed would be noise.
		when(entitlements.check(MEMBER, ITEM))
				.thenReturn(denied(DenyReason.NO_ENTITLEMENT, AccessLevel.ENTITLED_UNLIMITED));

		assertThatThrownBy(() -> broker.open(MEMBER, request(Intent.STREAM)))
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo(ErrorCode.NO_ENTITLEMENT);

		verify(changeLog, never()).record(any());
	}

	// ── step 5 (device cap now runs AFTER the copy claim — see ReadBrokerService, 2026-09-20:
	// checking it first meant a capped reader on a fully booked title got refused before ever
	// reaching the queue-join branch, and could never queue at all) ──────────────────────────

	@Test
	void eliteDeviceCapRefusalStopsBeforeTheGrantIsEverFetchedAndReleasesTheClaimedCopy() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.ENTITLED_CONCURRENT, 5, 14));
		// No existing lease to reuse (see the reuse-before-claim fix, 2026-09-20) — falls straight
		// through to a fresh claim, exactly as this test already expected.
		when(licences.activeLoanLeaseId(MEMBER.userId(), ITEM)).thenReturn(null);
		LeaseHandle handle = new LeaseHandle("token_1", MEMBER.institutionId(), ITEM, CLOCK.instant().plusSeconds(30));
		when(lease.claim(MEMBER.institutionId(), ITEM, 5)).thenReturn(Optional.of(handle));
		when(devices.admit(eq(MEMBER.userId()), any())).thenReturn(false);

		assertThatThrownBy(() -> broker.open(MEMBER, request(Intent.STREAM)))
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo(ErrorCode.DEVICE_LIMIT_REACHED);

		// A copy WAS claimed (there was one free) before the cap refused the reader — it must
		// be released, not left held by a session that never opened. See release() call below.
		verify(lease).release(handle);
		verifyNoInteractions(content, queue);
		verify(licences, never()).create(any(), any(), any(), anyInt(), any());
	}

	@Test
	void eliteDeviceCapRefusalOnAFullyBookedTitleJoinsTheQueueInstead() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.ENTITLED_CONCURRENT, 5, 14));
		when(lease.claim(MEMBER.institutionId(), ITEM, 5)).thenReturn(Optional.empty());
		// Checked before queuing (see the loan-already-held guard added 2026-09-20) and false
		// here — this reader has never had this title, so the guard is a no-op and this stays
		// the queuing path the test name describes.
		when(licences.hasActiveLoan(MEMBER.userId(), ITEM)).thenReturn(false);
		HoldView hold = new HoldView("hold_1", ITEM, "QUEUED", 3, 4, 14, CLOCK.instant(), null);
		when(queue.join(MEMBER.userId(), MEMBER.institutionId(), ITEM))
				.thenReturn(new QueueJoin.JoinResult(hold, true));

		ReadingSessionResponse response = broker.open(MEMBER, request(Intent.STREAM));

		// No copy was free, so the reader joins the queue — the device cap is never even
		// consulted, because joining a wait list spends no device slot.
		assertThat(response.licenceModel()).isEqualTo("ELITE");
		assertThat(response.holdCreatedAt()).isEqualTo(CLOCK.instant());
		verifyNoInteractions(devices, content);
		verify(licences, never()).create(any(), any(), any(), anyInt(), any());
	}

	@Test
	void subscriptionReadBypassesTheDeviceCap() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.ENTITLED_UNLIMITED, null, 14));
		when(devices.admit(eq(MEMBER.userId()), any())).thenReturn(false);
		when(licences.create(any(), any(), any(), anyInt(), any()))
				.thenReturn(new LicenceView("lic_subscription", MEMBER.userId(), ITEM,
						AccessLevel.ENTITLED_UNLIMITED, true, null, null));
		when(content.grant(any())).thenReturn(aGrant());

		assertThat(broker.open(MEMBER, request(Intent.STREAM)).licenceModel()).isEqualTo("SUBSCRIPTION");
		verifyNoInteractions(devices, lease, queue);
	}

	@Test
	void openAccessReadBypassesTheDeviceCap() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.OPEN_ACCESS, null, 0));
		when(devices.admit(eq(MEMBER.userId()), any())).thenReturn(false);
		when(licences.create(any(), any(), any(), anyInt(), any()))
				.thenReturn(new LicenceView("lic_open", MEMBER.userId(), ITEM,
						AccessLevel.OPEN_ACCESS, true, null, null));
		when(content.grant(any())).thenReturn(aGrant());

		assertThat(broker.open(MEMBER, request(Intent.STREAM)).licenceModel()).isEqualTo("OPEN_ACCESS");
		verifyNoInteractions(devices, lease, queue);
	}

	// ── step 4 ──────────────────────────────────────────────────────────────

	@Test
	void downloadOnEliteIsRefusedBeforeTheGrantIsFetchedOrACopyClaimed() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.ENTITLED_CONCURRENT, 5, 14));

		assertThatThrownBy(() -> broker.open(MEMBER, request(Intent.DOWNLOAD)))
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo(ErrorCode.DOWNLOAD_NOT_PERMITTED);

		// The whole point: we never asked anyone to do crypto or hold a copy for a
		// read we were always going to refuse.
		verifyNoInteractions(content, licences, lease, queue);
	}

	// ── step 5 ──────────────────────────────────────────────────────────────

	@Test
	void aFullEliteTitleJoinsTheQueueInsteadOfRefusingAndCreatesNoLicenceOrGrant() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.ENTITLED_CONCURRENT, 5, 14));
		when(lease.claim(MEMBER.institutionId(), ITEM, 5)).thenReturn(Optional.empty());
		when(licences.hasActiveLoan(MEMBER.userId(), ITEM)).thenReturn(false);
		HoldView hold = new HoldView("hold_1", ITEM, "QUEUED", 3, 4, 14, CLOCK.instant(), null);
		when(queue.join(MEMBER.userId(), MEMBER.institutionId(), ITEM))
				.thenReturn(new QueueJoin.JoinResult(hold, true));

		ReadingSessionResponse response = broker.open(MEMBER, request(Intent.STREAM));

		assertThat(response.loanId()).isNull();
		assertThat(response.canPersist()).isFalse();
		assertThat(response.content()).isNull();
		assertThat(response.licenceModel()).isEqualTo("ELITE");
		assertThat(response.holdCreatedAt()).isEqualTo(CLOCK.instant());

		verifyNoInteractions(content);
		verify(licences, never()).create(any(), any(), any(), anyInt(), any());
	}

	@Test
	void aReaderWhoAlreadyHoldsThisTitleGetsAClearAnswerInsteadOfBeingRefusedFromItsOwnQueue() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.ENTITLED_CONCURRENT, 5, 14));
		when(lease.claim(MEMBER.institutionId(), ITEM, 5)).thenReturn(Optional.empty());
		when(licences.hasActiveLoan(MEMBER.userId(), ITEM)).thenReturn(true);

		// Without this check, falling through to queue.join() here hit that method's OWN
		// already-holds-this-loan guard (added the same day, for the direct POST /api/v1/holds
		// endpoint) and surfaced as an unmapped VALIDATION_FAILED the client could only show as
		// a generic failure — even though this reader was not asking to queue at all, only to
		// read something they already have. NO_COPIES_AVAILABLE is a code the client already
		// renders a real message for.
		assertThatThrownBy(() -> broker.open(MEMBER, request(Intent.STREAM)))
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo(ErrorCode.NO_COPIES_AVAILABLE);

		verifyNoInteractions(queue, content);
		verify(licences, never()).create(any(), any(), any(), anyInt(), any());
	}

	@Test
	void reopeningATitleWithAnActiveLoanExtendsItsExistingLeaseRatherThanClaimingASecondOne() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.ENTITLED_CONCURRENT, 2, 14));
		when(licences.activeLoanLeaseId(MEMBER.userId(), ITEM)).thenReturn("lease_existing");
		when(lease.extend(any(), any())).thenReturn(true);
		when(licences.create(any(), any(), any(), anyInt(), any())).thenReturn(
				new LicenceView("loan_1", MEMBER.userId(), ITEM, AccessLevel.ENTITLED_CONCURRENT, false, null, "lease_existing"));
		when(content.grant(any())).thenReturn(aGrant());

		// THE bug this pins: before this reuse-before-claim fix, this method called
		// `lease.claim()` unconditionally, so a reader who already had an active loan (with its
		// own lease from borrowing) got a SECOND, entirely separate lease on every single open —
		// one reader on a 2-copy title consumed both copies alone. Reusing the existing lease via
		// extend() means `lease.claim()` must never even be called on this path.
		ReadingSessionResponse response = broker.open(MEMBER, request(Intent.STREAM));

		assertThat(response.content()).isNotNull();
		verify(lease, never()).claim(any(), any(), anyInt());
		// atLeastOnce, not exactly once: Step 8 extends the SAME handle again to the full session
		// TTL after the content grant succeeds — this is about the lease being reused at all, not
		// how many times the existing code path happens to extend it.
		verify(lease, atLeastOnce()).extend(eq(new LeaseHandle("lease_existing", MEMBER.institutionId(), ITEM, CLOCK.instant())), any());
	}

	@Test
	void anAlreadyQueuedReaderGetsTheSamePlacedAtNotANewOne() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.ENTITLED_CONCURRENT, 5, 14));
		when(lease.claim(any(), any(), anyInt())).thenReturn(Optional.empty());
		Instant originallyPlacedAt = CLOCK.instant().minusSeconds(3600);
		HoldView existing = new HoldView("hold_1", ITEM, "QUEUED", 2, 4, 14, originallyPlacedAt, null);
		when(queue.join(any(), any(), any())).thenReturn(new QueueJoin.JoinResult(existing, false));

		ReadingSessionResponse response = broker.open(MEMBER, request(Intent.STREAM));

		assertThat(response.holdCreatedAt()).isEqualTo(originallyPlacedAt);
	}

	// ── subscription / open-access happy path — no lease, no queue ──────────

	@Test
	void aSubscriptionReadNeverTouchesTheLeaseOrTheQueue() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.ENTITLED_UNLIMITED, null, 14));
		when(licences.create(MEMBER, ITEM, AccessLevel.ENTITLED_UNLIMITED, 14, null))
				.thenReturn(new LicenceView("lic_1", MEMBER.userId(), ITEM, AccessLevel.ENTITLED_UNLIMITED,
						true, CLOCK.instant().plusSeconds(1_209_600), null));
		when(content.grant(any())).thenReturn(aGrant());

		ReadingSessionResponse response = broker.open(MEMBER, request(Intent.STREAM));

		assertThat(response.licenceModel()).isEqualTo("SUBSCRIPTION");
		assertThat(response.holdCreatedAt()).isNull();
		verifyNoInteractions(lease, queue);
	}

	// ── elite happy path — claim, create, grant, extend, forward ────────────

	@Test
	void anEliteReadClaimsBeforeCreatingAndExtendsAfterTheGrant() {
		LeaseHandle handle = new LeaseHandle("token_1", MEMBER.institutionId(), ITEM, CLOCK.instant().plusSeconds(30));
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.ENTITLED_CONCURRENT, 5, 14));
		when(lease.claim(MEMBER.institutionId(), ITEM, 5)).thenReturn(Optional.of(handle));
		LicenceView licence = new LicenceView("lic_2", MEMBER.userId(), ITEM, AccessLevel.ENTITLED_CONCURRENT,
				false, CLOCK.instant().plusSeconds(1_209_600), "token_1");
		when(licences.create(MEMBER, ITEM, AccessLevel.ENTITLED_CONCURRENT, 14, "token_1")).thenReturn(licence);
		when(content.grant(any())).thenReturn(aGrant());
		// Extended to THIS SESSION's own expiry (CLOCK + SESSION_TTL), not the licence's — see
		// ReadBrokerService.open()'s Step 8 comment. A re-fetched ELITE loan can have a null
		// expiresAt (no subscription-style due date), which this used to pass straight to
		// copyLease.extend() and NPE; found via device testing, 2026-08-25.
		when(lease.extend(handle, CLOCK.instant().plusSeconds(300))).thenReturn(true);

		ReadingSessionResponse response = broker.open(MEMBER, request(Intent.STREAM));

		assertThat(response.licenceModel()).isEqualTo("ELITE");
		assertThat(response.canPersist()).isFalse();
		assertThat(response.holdCreatedAt()).isNull(); // a free copy was claimed — no hold was created

		var order = org.mockito.Mockito.inOrder(lease, licences, content, lease);
		order.verify(lease).claim(MEMBER.institutionId(), ITEM, 5);
		order.verify(licences).create(MEMBER, ITEM, AccessLevel.ENTITLED_CONCURRENT, 14, "token_1");
		order.verify(content).grant(any());
		order.verify(lease).extend(handle, CLOCK.instant().plusSeconds(300));
	}

	@Test
	void aFailedExtendReconcilesTheItemImmediatelyRatherThanWaiting() {
		LeaseHandle handle = new LeaseHandle("token_1", MEMBER.institutionId(), ITEM, CLOCK.instant().plusSeconds(30));
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.ENTITLED_CONCURRENT, 5, 14));
		when(lease.claim(any(), any(), anyInt())).thenReturn(Optional.of(handle));
		LicenceView licence = new LicenceView("lic_3", MEMBER.userId(), ITEM, AccessLevel.ENTITLED_CONCURRENT,
				false, CLOCK.instant().plusSeconds(1_209_600), "token_1");
		when(licences.create(any(), any(), any(), anyInt(), any())).thenReturn(licence);
		when(content.grant(any())).thenReturn(aGrant());
		when(lease.extend(any(), any())).thenReturn(false); // the one extend attempt fails

		broker.open(MEMBER, request(Intent.STREAM));

		verify(reconciler).reconcile(ITEM);
	}

	@Test
	void returnsSessionEvenWhenExtendAndReconcileBothFail() {
		// ACCEPTED GAP — see ReadBrokerService Step 8 comment.
		// Design: "recover, never rollback". The reader holds the licence; refusing now
		// would be worse than a copy count that is temporarily one short. The 30-second
		// claim TTL self-heals the slot without any action from the caller.
		LeaseHandle handle = new LeaseHandle("token_1", MEMBER.institutionId(), ITEM, CLOCK.instant().plusSeconds(30));
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.ENTITLED_CONCURRENT, 5, 14));
		when(lease.claim(any(), any(), anyInt())).thenReturn(Optional.of(handle));
		LicenceView licence = new LicenceView("lic_3b", MEMBER.userId(), ITEM, AccessLevel.ENTITLED_CONCURRENT,
				false, CLOCK.instant().plusSeconds(1_209_600), "token_1");
		when(licences.create(any(), any(), any(), anyInt(), any())).thenReturn(licence);
		when(content.grant(any())).thenReturn(aGrant());
		when(lease.extend(any(), any())).thenReturn(false); // extend fails
		// reconciler.reconcile() is a void mock — it does nothing but doesn't throw either.
		// The response must still be complete and valid.

		ReadingSessionResponse response = broker.open(MEMBER, request(Intent.STREAM));

		assertThat(response).isNotNull();
		assertThat(response.loanId()).isEqualTo("lic_3b");
		assertThat(response.licenceModel()).isEqualTo("ELITE");
		verify(reconciler).reconcile(ITEM); // reconcile was triggered despite the failed extend
	}

	@Test
	void anythingFailingAfterTheClaimGivesTheCopyBack() {
		LeaseHandle handle = new LeaseHandle("token_1", MEMBER.institutionId(), ITEM, CLOCK.instant().plusSeconds(30));
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.ENTITLED_CONCURRENT, 5, 14));
		when(lease.claim(any(), any(), anyInt())).thenReturn(Optional.of(handle));
		when(licences.create(any(), any(), any(), anyInt(), any()))
				.thenThrow(new RuntimeException("mongo is unreachable"));

		assertThatThrownBy(() -> broker.open(MEMBER, request(Intent.STREAM)))
				.isInstanceOf(RuntimeException.class);

		verify(lease).release(handle);
	}

	// ── the pass-through test — task 27 ──────────────────────────────────────

	@Test
	void theGrantIsForwardedFieldForFieldAndNameForName() {
		Encryption sentinel = new Encryption("AES-256-GCM", "nonce(12) || ciphertext || tag(16)",
				"WRAPPED-BEK-SENTINEL-9f2b1c", "RSA-OAEP-256", "bek_2026_08", "sha256:9f2b1c");
		SignedUrl signedUrl = new SignedUrl("https://storage.tf/x.enc?sig=abc",
				CLOCK.instant().plusSeconds(900), 2170674L, 2170646L, "application/pdf");
		IndexUrl indexUrl = new IndexUrl("https://storage.tf/x.idx?sig=def", true, 6120);
		ContentGrant grant = new ContentGrant(signedUrl, indexUrl, sentinel);

		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.ENTITLED_UNLIMITED, null, 14));
		when(licences.create(any(), any(), any(), anyInt(), any()))
				.thenReturn(new LicenceView("lic_4", MEMBER.userId(), ITEM, AccessLevel.ENTITLED_UNLIMITED,
						true, CLOCK.instant().plusSeconds(1_209_600), null));
		when(content.grant(any())).thenReturn(grant);

		ReadingSessionResponse response = broker.open(MEMBER, request(Intent.STREAM));

		// Same instances, not equal copies — nothing in the broker is permitted to construct
		// a new Encryption/SignedUrl/IndexUrl of its own.
		assertThat(response.content()).isSameAs(signedUrl);
		assertThat(response.index()).isSameAs(indexUrl);
		assertThat(response.encryption()).isSameAs(sentinel);

		// And the field that actually breaks a device if it is ever touched:
		assertThat(response.encryption().wrappedBek()).isEqualTo("WRAPPED-BEK-SENTINEL-9f2b1c");
	}

	@Test
	void theLoanProofCarriesTheJustCreatedLicenceNotAnEarlierOne() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.ENTITLED_UNLIMITED, null, 14));
		Instant dueAt = CLOCK.instant().plusSeconds(1_209_600);
		when(licences.create(any(), any(), any(), anyInt(), any()))
				.thenReturn(new LicenceView("lic_5", MEMBER.userId(), ITEM, AccessLevel.ENTITLED_UNLIMITED, true, dueAt, null));
		when(content.grant(any())).thenReturn(aGrant());

		broker.open(MEMBER, request(Intent.STREAM));

		verify(content).grant(org.mockito.ArgumentMatchers.argThat(req ->
				req.loanProof().loanId().equals("lic_5") && req.loanProof().dueAt().equals(dueAt)));
	}

	@Test
	void theExtendIsSkippedEntirelyForNonEliteTiers() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.OPEN_ACCESS, null, 0));
		when(licences.create(any(), any(), any(), anyInt(), any()))
				.thenReturn(new LicenceView("lic_6", MEMBER.userId(), ITEM, AccessLevel.OPEN_ACCESS, true, null, null));
		when(content.grant(any())).thenReturn(aGrant());

		broker.open(MEMBER, request(Intent.STREAM));

		verifyNoInteractions(lease);
	}

	private static ContentGrant aGrant() {
		return new ContentGrant(
				new SignedUrl("https://storage.tf/default.enc", Instant.parse("2026-08-20T10:15:00Z"),
						100L, 90L, "application/pdf"),
				null,
				new Encryption("AES-256-GCM", "nonce(12) || ciphertext || tag(16)", "bek", "RSA-OAEP-256", "k1", "fp1"));
	}
}
