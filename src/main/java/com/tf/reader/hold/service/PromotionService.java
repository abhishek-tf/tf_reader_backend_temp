package com.tf.reader.hold.service;

import com.tf.reader.catalogue.api.EntitlementDecision;
import com.tf.reader.catalogue.api.EntitlementQuery;
import com.tf.reader.catalogue.api.SubjectRef;
import com.tf.reader.hold.entity.Hold;
import com.tf.reader.hold.entity.HoldStatus;
import com.tf.reader.hold.entity.Offer;
import com.tf.reader.hold.repository.HoldRepository;
import com.tf.reader.hold.repository.HoldWrites;
import com.tf.reader.library.api.ChangeLog;
import com.tf.reader.library.api.ChangeReason;
import com.tf.reader.library.api.ChangeRecord;
import com.tf.reader.reading.api.CopyLease;
import com.tf.reader.reading.api.LeaseHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Turns a freed copy into an offer for the next in line. The twenty lines
// the module exists for — everything above this is plumbing to let this run
// safely under two readers at once.
@Service
public class PromotionService {

    private static final Logger log = LoggerFactory.getLogger(PromotionService.class);

    // Compare-and-delete, never a plain DEL — if this call overran its own
    // lock TTL, a blind delete would release somebody else's lock and two
    // promotions would run at once.
    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final HoldRepository holds;
    private final HoldWrites writes;
    private final StringRedisTemplate redis;
    private final CopyLease lease;
    private final EntitlementQuery entitlements;
    private final HoldProperties props;
    private final ChangeLog changeLog;
    private final Clock clock;

    public PromotionService(HoldRepository holds, HoldWrites writes, StringRedisTemplate redis,
                             CopyLease lease, EntitlementQuery entitlements, HoldProperties props,
                             ChangeLog changeLog, Clock clock) {
        this.holds = holds;
        this.writes = writes;
        this.redis = redis;
        this.lease = lease;
        this.entitlements = entitlements;
        this.props = props;
        this.changeLog = changeLog;
        this.clock = clock;
    }

    // fromToken is the lease token the PREVIOUS holder's offer carried, or
    // null when nobody currently holds this copy (a first-time grant).
    public boolean promoteNext(String scope, String itemId, String fromToken) {
        String lockKey = QueueKeys.promoteLockKey(scope, itemId);
        String token = UUID.randomUUID().toString();

        Boolean gotLock = redis.opsForValue().setIfAbsent(lockKey, token, props.getPromoteLockTtl());
        if (gotLock == null || !gotLock) {
            log.info("promotion: already in progress scope={} itemId={}", scope, itemId);
            // fromToken is the copy THIS call was handed to carry forward — dropping it here
            // silently, rather than releasing it like the "queue empty" branch below does,
            // orphaned the copy for its full ~offerWindow+leaseSlack lifetime (~16 minutes) any
            // time two offers on the same title lapsed in the same sweep tick. Releasing it
            // makes the copy claimable again immediately — first-come-first-served rather than
            // guaranteed to the next queued reader, but that is a small degradation next to a
            // copy silently vanishing from the count. See queue audit, 2026-09-20.
            if (fromToken != null) {
                lease.release(fromToken);
            }
            return false;
        }
        try {
            boolean promoted = promoteUnderLock(scope, itemId, fromToken);
            log.info("promotion: attempt finished scope={} itemId={} promoted={}", scope, itemId, promoted);
            return promoted;
        } catch (RuntimeException e) {
            // Never let this escape into loan's return path — if it throws,
            // returning a book fails with a 500 and someone debugs their
            // own lane first.
            log.error("promotion failed for {}:{}", scope, itemId, e);
            return false;
        } finally {
            redis.execute(RELEASE_LOCK, List.of(lockKey), token);
        }
    }

    private boolean promoteUnderLock(String scope, String itemId, String fromToken) {
        String queueKey = QueueKeys.queueKey(scope, itemId);
        Set<String> head = redis.opsForZSet().range(queueKey, 0, 0);
        if (head == null || head.isEmpty()) {
            // Nobody waiting — the copy is genuinely free. If somebody was
            // holding it, hand the slot back rather than leaking it.
            log.info("promotion: queue empty scope={} itemId={}, releasing slot", scope, itemId);
            if (fromToken != null) {
                lease.release(fromToken);
            }
            return false;
        }
        String member = head.iterator().next();
        String nextUserId = QueueKeys.userOf(member);

        Optional<Hold> maybeHold = holds.findByScopeAndItemIdAndUserId(scope, itemId, nextUserId)
                .filter(h -> h.getStatus() == HoldStatus.QUEUED);
        if (maybeHold.isEmpty()) {
            // Redis and Mongo disagree — the reconciler's job, not this
            // call's. Drop the stale row so the next call doesn't loop on it.
            log.warn("promotion: Redis/Mongo disagree scope={} itemId={} userId={}, dropping stale queue row",
                    scope, itemId, nextUserId);
            redis.opsForZSet().remove(queueKey, member);
            // Same reasoning as the lock-contention branch above: this call was still holding
            // fromToken's copy and is about to return false without doing anything with it.
            if (fromToken != null) {
                lease.release(fromToken);
            }
            return false;
        }
        Hold hold = maybeHold.get();
        log.info("promotion: promoting holdId={} scope={} itemId={} userId={}", hold.getHoldId(), scope, itemId,
                nextUserId);

        Instant now = clock.instant();
        Instant until = now.plus(props.getOfferWindow()).plus(props.getLeaseSlack());

        String newToken;
        if (fromToken == null) {
            EntitlementDecision decision = entitlements.check(new SubjectRef(nextUserId, scope), itemId);
            Integer total = decision.copies();
            if (total == null) {
                return false;
            }
            Optional<LeaseHandle> claimed = lease.claim(scope, itemId, total);
            if (claimed.isEmpty()) {
                log.info("promotion: fresh claim failed scope={} itemId={} userId={}", scope, itemId, nextUserId);
                return false; // couldn't actually get the copy this time
            }
            // Extend to cover the full offer window before writing the Mongo offer.
            // claim() only gives a 30-second CLAIM_TTL; without this extend the Redis
            // reservation vanishes while the offer is still "valid", and the reader gets
            // SESSION_FETCH_FAILED when they accept at any normal reaction time.
            LeaseHandle handle = claimed.get();
            if (!lease.extend(handle, until)) {
                log.warn("promotion: extend failed after fresh claim scope={} itemId={} userId={}, releasing",
                        scope, itemId, nextUserId);
                lease.release(handle);
                return false;
            }
            newToken = handle.token();
        } else {
            // Reassign, never release-then-acquire — a release opens a
            // window, and a passing reader takes a copy somebody waited
            // three days for.
            newToken = "lease_" + UUID.randomUUID().toString().substring(0, 8);
            lease.reassign(scope, itemId, fromToken, newToken, until);
        }

        Offer offer = new Offer("offer_" + UUID.randomUUID().toString().substring(0, 8), now,
                now.plus(props.getOfferWindow()), newToken);
        Optional<Hold> offered = writes.offerIfQueued(hold.getHoldId(), offer);
        if (offered.isEmpty()) {
            log.info("promotion: lost race with a cancel holdId={} scope={} itemId={}", hold.getHoldId(), scope,
                    itemId);
            // newToken already holds the copy in Redis (claimed+extended above, or reassigned
            // from fromToken) but no Mongo Offer document ended up pointing at it — release it
            // rather than leaking it for its full extended lifetime. Safe even in the reassign
            // case: fromToken is already gone from the ZSET by this point, so releasing newToken
            // is the only way this copy becomes claimable again.
            lease.release(newToken);
            return false; // lost a race with a cancel — a later call reconciles
        }

        redis.opsForZSet().remove(queueKey, member); // not waiting any more — they're holding
        changeLog.record(ChangeRecord.forHold(nextUserId, ChangeReason.HOLD_PROMOTED, itemId, hold.getHoldId(), now));
        log.info("promotion: offered holdId={} scope={} itemId={} userId={} offerExpiresAt={}", hold.getHoldId(),
                scope, itemId, nextUserId, offer.getExpiresAt());
        return true;
    }
}
