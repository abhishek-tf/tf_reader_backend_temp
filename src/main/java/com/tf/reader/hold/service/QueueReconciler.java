package com.tf.reader.hold.service;

import com.tf.reader.hold.entity.Hold;
import com.tf.reader.hold.entity.HoldStatus;
import com.tf.reader.hold.repository.HoldRepository;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Rebuilds each title's Redis queue from Mongo's own truth. Unlike reading's lease
// reconciler, this needs no grace window: every hold write path (join, leave, promote,
// sweep) writes Mongo before it ever touches Redis, so Redis can only lag Mongo, never
// lead it — a Mongo row is always at least as current as anything already in the ZSET.
@Component
public class QueueReconciler {

    // Never lowered — only raised to at least the highest ticket Mongo has actually
    // handed out. A plain GET-then-SET would race a live join()'s own INCR and could
    // roll the counter backwards; this only ever moves it up.
    private static final DefaultRedisScript<Long> BUMP_TICKET_COUNTER = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local wanted = tonumber(ARGV[1])
            if wanted > current then
                redis.call('SET', KEYS[1], ARGV[1])
            end
            return 1
            """, Long.class);

    private final HoldRepository holds;
    private final StringRedisTemplate redis;

    public QueueReconciler(HoldRepository holds, StringRedisTemplate redis) {
        this.holds = holds;
        this.redis = redis;
    }

    @Scheduled(fixedDelayString = "${holds.reconcile-interval:5m}")
    public void reconcile() {
        reconcileAndCount();
    }

    /** Same rebuild, returning the number of (scope, itemId) queues touched. */
    public int reconcileAndCount() {
        Set<QueueKeys.Parsed> items = new HashSet<>();
        holds.findByStatus(HoldStatus.QUEUED)
                .forEach(h -> items.add(new QueueKeys.Parsed(h.getScope(), h.getItemId())));
        items.addAll(knownQueueItems());
        items.forEach(item -> reconcileOne(item.scope(), item.itemId()));
        return items.size();
    }

    private void reconcileOne(String scope, String itemId) {
        String queueKey = QueueKeys.queueKey(scope, itemId);

        // Read Redis before Mongo, not after: reversed, a hold that finishes join() between
        // the two reads would look like a Redis-only orphan and get wrongly evicted below.
        Set<String> actual = redis.opsForZSet().range(queueKey, 0, -1);
        List<Hold> queued = holds.findByScopeAndItemIdAndStatusOrderByTicketAsc(scope, itemId, HoldStatus.QUEUED);

        Set<String> expected = new HashSet<>();
        long maxTicket = 0;
        for (Hold hold : queued) {
            String member = QueueKeys.member(hold.getUserId());
            expected.add(member);
            redis.opsForZSet().add(queueKey, member, hold.getTicket());
            maxTicket = Math.max(maxTicket, hold.getTicket());
        }

        if (actual != null) {
            for (String member : actual) {
                if (!expected.contains(member)) {
                    redis.opsForZSet().remove(queueKey, member);
                }
            }
        }

        if (maxTicket > 0) {
            redis.execute(BUMP_TICKET_COUNTER, List.of(QueueKeys.ticketKey(scope, itemId)), Long.toString(maxTicket));
        }
    }

    // SCAN, not KEYS: KEYS walks the whole keyspace in one blocking call on Redis's single
    // command thread, so every join/accept/availability read queued behind it risks tripping
    // the 200ms command timeout — and this runs on a schedule, so that was a recurring burst of
    // failures, not a one-off. SCAN walks the same keyspace in small steps, interleaved with
    // other commands. See queue audit, 2026-09-20.
    private Set<QueueKeys.Parsed> knownQueueItems() {
        Set<QueueKeys.Parsed> parsed = new HashSet<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(QueueKeys.ALL_QUEUE_KEYS_PATTERN).count(500).build())) {
            cursor.forEachRemaining(key -> parsed.add(QueueKeys.parseQueueKey(key)));
        }
        return parsed;
    }
}
