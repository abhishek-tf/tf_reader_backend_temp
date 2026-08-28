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
import com.tf.reader.hold.api.AvailabilityQuery;
import com.tf.reader.hold.api.AvailabilitySnapshot;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
	private AvailabilityQuery availability;
	private ReconcilerService reconciler;
	private DeviceCapService devices;
	private RightsService rights;
	private ReadBrokerService broker;

	@BeforeEach
	void setUp() {
		entitlements = mock(EntitlementQuery.class);
		content = mock(ContentAccessGrant.class);
		licences = mock(LicenceCommand.class);
		lease = mock(CopyLease.class);
		availability = mock(AvailabilityQuery.class);
		reconciler = mock(ReconcilerService.class);
		devices = mock(DeviceCapService.class);
		rights = new RightsService(); // real: it is pure and cheap, no reason to fake it

		// Default: device cap always admits, unless a test says otherwise.
		when(devices.admit(anyString(), any())).thenReturn(true);

		broker = new ReadBrokerService(entitlements, content, licences, lease, availability,
				reconciler, devices, rights, CLOCK);
	}

	private ReadingSessionRequest request(Intent intent) {
		return new ReadingSessionRequest(ITEM, Format.PDF, intent, KEY, false);
	}

	private EntitlementDecision entitled(AccessLevel level, Integer copies, int loanPeriodDays) {
		return new EntitlementDecision(true, level, "ent_1", copies, loanPeriodDays, null, null);
	}

	private EntitlementDecision denied(DenyReason reason) {
		return new EntitlementDecision(false, null, null, null, 0, null, reason);
	}

	// ── step 1 ──────────────────────────────────────────────────────────────

	@Test
	void anUndecodableDeviceKeyIsRejectedBeforeAnythingElseRuns() {
		ReadingSessionRequest bad = new ReadingSessionRequest(ITEM, Format.PDF, Intent.STREAM, "!!! not base64 !!!", false);

		assertThatThrownBy(() -> broker.open(MEMBER, bad))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo(ErrorCode.INVALID_DEVICE_PUBLIC_KEY);

		verifyNoInteractions(entitlements, devices, content, licences, lease, availability);
	}

	// ── step 2 — every DenyReason maps to its own code ───────────────────────

	@Test
	void noEntitlementRefusesBeforeAnythingDownstreamRuns() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(denied(DenyReason.NO_ENTITLEMENT));

		assertThatThrownBy(() -> broker.open(MEMBER, request(Intent.STREAM)))
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo(ErrorCode.NO_ENTITLEMENT);

		verifyNoInteractions(devices, content, licences, lease, availability);
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

	// ── step 3 ──────────────────────────────────────────────────────────────

	@Test
	void eliteDeviceCapRefusalStopsBeforeTheGrantIsEverFetched() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.ENTITLED_CONCURRENT, 5, 14));
		when(devices.admit(eq(MEMBER.userId()), any())).thenReturn(false);

		assertThatThrownBy(() -> broker.open(MEMBER, request(Intent.STREAM)))
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo(ErrorCode.DEVICE_LIMIT_REACHED);

		verifyNoInteractions(content, licences, lease, availability);
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
		verifyNoInteractions(devices, lease, availability);
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
		verifyNoInteractions(devices, lease, availability);
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
		verifyNoInteractions(content, licences, lease, availability);
	}

	// ── step 5 ──────────────────────────────────────────────────────────────

	@Test
	void aFullEliteTitleRefusesAndCreatesNothingAndFetchesNothing() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.ENTITLED_CONCURRENT, 5, 14));
		when(lease.claim(MEMBER.institutionId(), ITEM, 5)).thenReturn(Optional.empty());
		when(availability.forItem(MEMBER.institutionId(), ITEM, 5))
				.thenReturn(new AvailabilitySnapshot(0, 4, null, CLOCK.instant()));

		assertThatThrownBy(() -> broker.open(MEMBER, request(Intent.STREAM)))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo(ErrorCode.NO_COPIES_AVAILABLE);

		verifyNoInteractions(licences, content);
	}

	@Test
	void theFullTitleRefusalDescribesTheQueueWhenAvailabilityCanAnswer() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.ENTITLED_CONCURRENT, 5, 14));
		when(lease.claim(any(), any(), anyInt())).thenReturn(Optional.empty());
		when(availability.forItem(any(), any(), any()))
				.thenReturn(new AvailabilitySnapshot(0, 4, 2, CLOCK.instant()));

		assertThatThrownBy(() -> broker.open(MEMBER, request(Intent.STREAM)))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("4 reader")
				.hasMessageContaining("already 2 in line");
	}

	@Test
	void theFullTitleRefusalNeverBecomesA500WhenAvailabilityItselfFails() {
		when(entitlements.check(MEMBER, ITEM)).thenReturn(entitled(AccessLevel.ENTITLED_CONCURRENT, 5, 14));
		when(lease.claim(any(), any(), anyInt())).thenReturn(Optional.empty());
		when(availability.forItem(any(), any(), any())).thenThrow(new RuntimeException("redis is down"));

		// Best-effort: the queue detail is lost, but the refusal itself must still be a clean 409.
		assertThatThrownBy(() -> broker.open(MEMBER, request(Intent.STREAM)))
				.isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).code())
				.isEqualTo(ErrorCode.NO_COPIES_AVAILABLE);
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
		assertThat(response.queue()).isNull();
		verifyNoInteractions(lease, availability);
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
		assertThat(response.queue()).isNull(); // this endpoint never places anyone in a queue

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
