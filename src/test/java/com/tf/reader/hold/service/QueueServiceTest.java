package com.tf.reader.hold.service;

import com.tf.reader.catalogue.api.AccessLevel;
import com.tf.reader.catalogue.api.DenyReason;
import com.tf.reader.catalogue.api.EntitlementDecision;
import com.tf.reader.catalogue.api.EntitlementQuery;
import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.hold.entity.Hold;
import com.tf.reader.hold.entity.HoldStatus;
import com.tf.reader.hold.entity.Offer;
import com.tf.reader.hold.repository.HoldRepository;
import com.tf.reader.hold.repository.HoldWrites;
import com.tf.reader.library.api.ChangeLog;
import com.tf.reader.library.api.ChangeReason;
import com.tf.reader.library.api.ChangeRecord;
import com.tf.reader.loan.api.LicenceCommand;
import com.tf.reader.loan.api.LicenceView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Everything here short-circuits before touching Mongo or Redis, so a plain
// mock is enough — the behaviour that actually needs a real database lives
// in QueueServiceIT instead.
class QueueServiceTest {

    private final HoldRepository holds = mock(HoldRepository.class);
    private final HoldWrites writes = mock(HoldWrites.class);
    private final org.springframework.data.redis.core.StringRedisTemplate redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
    private final EntitlementQuery entitlements = mock(EntitlementQuery.class);
    private final PromotionService promotion = mock(PromotionService.class);
    private final LicenceCommand loans = mock(LicenceCommand.class);
    private final ChangeLog changeLog = mock(ChangeLog.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-17T09:00:00Z"), ZoneOffset.UTC);
    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> zsetOps = mock(ZSetOperations.class);

    private QueueService queue;

    @BeforeEach
    void setUp() {
        when(redis.opsForZSet()).thenReturn(zsetOps);
        queue = new QueueService(holds, writes, redis, entitlements, promotion, loans, changeLog, clock);
    }

    @Test
    void joinRefusesATitleWithNoCopyLimit() {
        CurrentUser me = new CurrentUser("user_a", UserType.INSTITUTION, "inst_1", List.of(), List.of());
        when(entitlements.check(any(), any())).thenReturn(
                new EntitlementDecision(true, AccessLevel.ENTITLED_UNLIMITED, "ent_1", null, 14, null, null));

        // "No copy limit" reuses VALIDATION_FAILED — the contract doesn't
        // mint a new code for "there was never a queue here to join."
        assertThatThrownBy(() -> queue.join(me, "item_1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void joinPassesTheDenyReasonThroughUnchanged() {
        CurrentUser me = new CurrentUser("user_a", UserType.INSTITUTION, "inst_1", List.of(), List.of());
        when(entitlements.check(any(), any())).thenReturn(
                new EntitlementDecision(false, null, null, null, 0, null, DenyReason.ENTITLEMENT_EXPIRED));

        // "Your subscription lapsed" and "your library never had this" are
        // different sentences — never collapsed into one code.
        assertThatThrownBy(() -> queue.join(me, "item_1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.ENTITLEMENT_EXPIRED);
    }

    @Test
    void joinRefusesATokenWithNoInstitutionScope() {
        CurrentUser individualReader = new CurrentUser("user_a", UserType.INDIVIDUAL, null, List.of(), List.of());

        assertThatThrownBy(() -> queue.join(individualReader, "item_1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void leaveRemovesTheQueueEntryAndPromotesTheNextReaderOnlyIfOffered() {
        CurrentUser me = new CurrentUser("user_a", UserType.INSTITUTION, "inst_1", List.of(), List.of());
        Offer offer = new Offer("offer_1", Instant.now(), Instant.now().plusSeconds(900), "lease_1");
        Hold hold = Hold.queued("user_a", "inst_1", "item_1", 1, Instant.now());
        hold.setStatus(HoldStatus.OFFERED);
        hold.setOffer(offer);
        when(writes.deleteOwn("hold_1", "user_a")).thenReturn(Optional.of(hold));

        queue.leave(me, "hold_1");

        verify(zsetOps).remove(anyString(), eq(QueueKeys.member("user_a")));
        verify(promotion).promoteNext("inst_1", "item_1", "lease_1");
        verify(changeLog).record(ChangeRecord.forHold("user_a", ChangeReason.HOLD_CANCELLED, "item_1", hold.getHoldId(), clock.instant()));
    }

    @Test
    void leaveIsANoOpWhenTheHoldWasAlreadyGoneOrNeverTheirs() {
        CurrentUser me = new CurrentUser("user_a", UserType.INSTITUTION, "inst_1", List.of(), List.of());
        when(writes.deleteOwn("hold_1", "user_a")).thenReturn(Optional.empty());

        queue.leave(me, "hold_1");

        verifyNoInteractions(promotion);
        verify(zsetOps, never()).remove(anyString(), anyString());
    }

    @Test
    void acceptCreatesTheLoanFromTheOffersLeaseToken() {
        CurrentUser me = new CurrentUser("user_a", UserType.INSTITUTION, "inst_1", List.of(), List.of());
        Offer offer = new Offer("offer_1", Instant.now(), Instant.now().plusSeconds(900), "lease_1");
        Hold hold = Hold.queued("user_a", "inst_1", "item_1", 1, Instant.now());
        hold.setStatus(HoldStatus.OFFERED);
        hold.setOffer(offer);
        when(writes.claimIfLive("hold_1", "user_a", clock.instant())).thenReturn(Optional.of(hold));
        when(entitlements.check(any(), any())).thenReturn(
                new EntitlementDecision(true, AccessLevel.ENTITLED_CONCURRENT, "ent_1", 2, 14, null, null));
        when(loans.create(any(), eq("item_1"), eq(AccessLevel.ENTITLED_CONCURRENT), eq(14), eq("lease_1")))
                .thenReturn(new LicenceView("loan_1", "user_a", "item_1", AccessLevel.ENTITLED_CONCURRENT,
                        false, Instant.parse("2026-08-31T09:00:00Z"), "lease_1"));

        var response = queue.accept(me, "hold_1");

        assertThat(response.loanId()).isEqualTo("loan_1");
        assertThat(response.userId()).isEqualTo("user_a");
        assertThat(response.institutionId()).isEqualTo("inst_1");
        assertThat(response.licenceModel()).isEqualTo("ELITE");
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.dueAt()).isEqualTo(Instant.parse("2026-08-31T09:00:00Z"));
        verify(changeLog).record(ChangeRecord.forLoan("user_a", ChangeReason.LOAN_CREATED, "item_1", "loan_1", clock.instant()));
    }

    @Test
    void acceptRefusesWithOfferExpiredWhenNothingIsLive() {
        CurrentUser me = new CurrentUser("user_a", UserType.INSTITUTION, "inst_1", List.of(), List.of());
        when(writes.claimIfLive("hold_1", "user_a", clock.instant())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queue.accept(me, "hold_1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.OFFER_EXPIRED);

        verifyNoInteractions(loans);
    }

    @Test
    void placedRecordsWhetherTheJoinWasNewOrIdempotent() {
        // Not a behavioural test — just guards the record shape callers rely on.
        var view = new com.tf.reader.hold.api.HoldView("hold_1", "item_1", "QUEUED", 1, 1, 14, Instant.now(), null);
        var placed = new QueueService.Placed(view, true);
        assertThat(placed.created()).isTrue();
        assertThat(placed.view()).isSameAs(view);
    }
}
