package com.tf.reader.hold.service;

import com.tf.reader.hold.entity.Hold;
import com.tf.reader.hold.entity.HoldStatus;
import com.tf.reader.hold.repository.HoldRepository;
import com.tf.reader.hold.repository.HoldWrites;
import com.tf.reader.library.api.ChangeLog;
import com.tf.reader.library.api.ChangeReason;
import com.tf.reader.library.api.ChangeRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

// Scheduled job that expires unclaimed offers. Not a Redis expiry — a key
// vanishing runs no code. An offer lapses because THIS found it in Mongo.
@Component
public class OfferSweeper {

    private static final Logger log = LoggerFactory.getLogger(OfferSweeper.class);

    private final HoldRepository holds;
    private final HoldWrites writes;
    private final StringRedisTemplate redis;
    private final PromotionService promotion;
    private final ChangeLog changeLog;
    private final Clock clock;

    public OfferSweeper(HoldRepository holds, HoldWrites writes, StringRedisTemplate redis,
                         PromotionService promotion, ChangeLog changeLog, Clock clock) {
        this.holds = holds;
        this.writes = writes;
        this.redis = redis;
        this.promotion = promotion;
        this.changeLog = changeLog;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${holds.sweep-interval:10s}")
    public void sweep() {
        Instant now = clock.instant();
        List<Hold> candidates = holds.findByStatusAndOfferExpiresAtBefore(HoldStatus.OFFERED, now);

        for (Hold candidate : candidates) {
            try {
                sweepOne(candidate.getHoldId(), now);
            } catch (RuntimeException e) {
                // One odd record must not freeze every queue in the system —
                // catch, log, carry on. Loan's own sweeper has the same rule.
                log.error("sweep failed for hold {}", candidate.getHoldId(), e);
            }
        }
    }

    private void sweepOne(String holdId, Instant now) {
        // Guarded: OFFERED and the deadline has passed. If accept won the
        // race a moment earlier, this matches nothing and leaves them alone.
        writes.expireIfLapsed(holdId, now).ifPresent(expired -> {
            String queueKey = QueueKeys.queueKey(expired.getScope(), expired.getItemId());
            redis.opsForZSet().remove(queueKey, QueueKeys.member(expired.getUserId()));
            changeLog.record(ChangeRecord.forHold(expired.getUserId(), ChangeReason.HOLD_OFFER_EXPIRED,
                    expired.getItemId(), expired.getHoldId(), now));

            // They rejoin at the back, not the front, if they want back in —
            // holding a place for somebody who didn't answer is no queue at all.
            promotion.promoteNext(expired.getScope(), expired.getItemId(), expired.getOffer().getLeaseToken());
        });
    }
}
