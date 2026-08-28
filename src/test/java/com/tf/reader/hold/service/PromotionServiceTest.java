package com.tf.reader.hold.service;

import com.tf.reader.catalogue.api.EntitlementQuery;
import com.tf.reader.hold.repository.HoldRepository;
import com.tf.reader.hold.repository.HoldWrites;
import com.tf.reader.library.api.ChangeLog;
import com.tf.reader.reading.api.CopyLease;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// The branches that don't need a real database: the lock, and an empty
// queue. Actually handing a copy to somebody needs real Redis + real Mongo
// interacting correctly — that's PromotionIT.
class PromotionServiceTest {

    private final HoldRepository holds = mock(HoldRepository.class);
    private final HoldWrites writes = mock(HoldWrites.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final CopyLease lease = mock(CopyLease.class);
    private final EntitlementQuery entitlements = mock(EntitlementQuery.class);
    private final HoldProperties props = new HoldProperties();
    private final ChangeLog changeLog = mock(ChangeLog.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-17T09:00:00Z"), ZoneOffset.UTC);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> zsetOps = mock(ZSetOperations.class);

    private PromotionService promotion;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForZSet()).thenReturn(zsetOps);
        promotion = new PromotionService(holds, writes, redis, lease, entitlements, props, changeLog, clock);
    }

    @Test
    void returnsFalseWhenSomebodyElseIsAlreadyPromotingThisTitle() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThat(promotion.promoteNext("inst_1", "item_1", null)).isFalse();
        verifyNoInteractions(lease);
    }

    @Test
    void returnsFalseAndReleasesTheLockWhenNobodyIsWaiting() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(zsetOps.range(anyString(), eq(0L), eq(0L))).thenReturn(Set.of());

        assertThat(promotion.promoteNext("inst_1", "item_1", null)).isFalse();
        verify(redis).execute(any(), anyList(), anyString());
    }

    @Test
    void dropsAStaleRedisRowWhenMongoDisagreesWithIt() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(zsetOps.range(anyString(), eq(0L), eq(0L))).thenReturn(Set.of("u:user_a"));
        when(holds.findByScopeAndItemIdAndUserId("inst_1", "item_1", "user_a")).thenReturn(java.util.Optional.empty());

        boolean promoted = promotion.promoteNext("inst_1", "item_1", null);

        assertThat(promoted).isFalse();
        verify(zsetOps).remove("queue:inst_1:item_1", "u:user_a");
    }
}
