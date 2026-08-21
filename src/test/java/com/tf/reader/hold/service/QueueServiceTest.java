package com.tf.reader.hold.service;

import com.tf.reader.catalogue.api.AccessLevel;
import com.tf.reader.catalogue.api.DenyReason;
import com.tf.reader.catalogue.api.EntitlementDecision;
import com.tf.reader.catalogue.api.EntitlementQuery;
import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.hold.repository.HoldRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Everything here short-circuits before touching Mongo or Redis, so a plain
// mock is enough — the behaviour that actually needs a real database lives
// in QueueServiceIT instead.
class QueueServiceTest {

    private final HoldRepository holds = mock(HoldRepository.class);
    private final org.springframework.data.redis.core.StringRedisTemplate redis =
            mock(org.springframework.data.redis.core.StringRedisTemplate.class);
    private final EntitlementQuery entitlements = mock(EntitlementQuery.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-17T09:00:00Z"), ZoneOffset.UTC);

    private QueueService queue;

    @BeforeEach
    void setUp() {
        queue = new QueueService(holds, redis, entitlements, clock);
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
    void placedRecordsWhetherTheJoinWasNewOrIdempotent() {
        // Not a behavioural test — just guards the record shape callers rely on.
        var view = new com.tf.reader.hold.api.HoldView("hold_1", "item_1", "QUEUED", 1, 1, 14, Instant.now(), null);
        var placed = new QueueService.Placed(view, true);
        assertThat(placed.created()).isTrue();
        assertThat(placed.view()).isSameAs(view);
    }
}
