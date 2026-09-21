package com.tf.reader.reading.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.tf.reader.reading.api.CopyLease;
import com.tf.reader.reading.api.LeaseHandle;

/**
 * Service implementation of {@link CopyLease}.
 *
 * <p>The copy counter is a per-item Redis ZSET (member = lease token, score = expiry
 * epoch millis) so a stale claim from a crashed caller evicts itself instead of leaking
 * a slot forever. {@code release(String)} only ever gets a bare token — every real
 * caller (loan return, borrow rollback, the expiry sweeper) has no scope or itemId to
 * hand back — so a companion reverse-index key maps token to item key, written in the
 * same script as the claim it belongs to.
 */
@Slf4j
@Service
public class CopyLeaseImpl implements CopyLease {

	private static final Duration CLAIM_TTL = Duration.ofSeconds(30);

	// Evicts anything past its expiry, then claims only if still under the limit —
	// eviction and the check must happen in the same round trip, or two requests
	// racing past a stale count could both believe they got the last copy.
	private static final DefaultRedisScript<Long> CLAIM = new DefaultRedisScript<>("""
			redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
			if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then
			    return 0
			end
			redis.call('ZADD', KEYS[1], ARGV[3], ARGV[4])
			redis.call('SET', KEYS[2], ARGV[5], 'PXAT', ARGV[3])
			return 1
			""", Long.class);

	// KEYS[1] is the reverse index — the only place that knows which item's counter a
	// bare token belongs to.
	private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
			local itemKey = redis.call('GET', KEYS[1])
			if not itemKey then
			    return 0
			end
			redis.call('DEL', KEYS[1])
			return redis.call('ZREM', itemKey, ARGV[1])
			""", Long.class);

	// Same slot, new token — never a release followed by a claim, which would open a
	// window for a passing reader to take the copy a promoted waiter was just handed.
	//
	// Guarded on the old token still being present, same as EXTEND below: without this, a
	// `fromToken` already reaped by CLAIM's own ZREMRANGEBYSCORE (or otherwise gone) made ZREM
	// a no-op while ZADD still ran — silently adding a net-new slot, so the ZSET could hold more
	// members than `copies` allows. `available()` then clamps to 0 forever while the phantom
	// lease sits there with a fresh, far-future expiry: every reader queues for a title that
	// actually has a free copy. See queue audit, 2026-09-20.
	private static final DefaultRedisScript<Long> REASSIGN = new DefaultRedisScript<>("""
			if redis.call('ZSCORE', KEYS[1], ARGV[1]) == false then
			    return 0
			end
			redis.call('ZREM', KEYS[1], ARGV[1])
			redis.call('DEL', KEYS[2])
			redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
			redis.call('SET', KEYS[3], ARGV[4], 'PXAT', ARGV[2])
			return 1
			""", Long.class);

	// Fails (returns 0) if the token isn't in the set any more — already expired or
	// already reassigned — so the caller retries or reconciles instead of extending a
	// lease that no longer counts against the limit.
	private static final DefaultRedisScript<Long> EXTEND = new DefaultRedisScript<>("""
			if redis.call('ZSCORE', KEYS[1], ARGV[2]) == false then
			    return 0
			end
			redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
			redis.call('PEXPIREAT', KEYS[2], ARGV[1])
			return 1
			""", Long.class);

	private final StringRedisTemplate redis;
	private final Clock clock;

	public CopyLeaseImpl(StringRedisTemplate redis, Clock clock) {
		this.redis = redis;
		this.clock = clock;
	}

	@Override
	public Optional<LeaseHandle> claim(String scope, String itemId, int copies) {
		if (copies <= 0) {
			return Optional.empty();
		}
		Instant now = clock.instant();
		Instant expiresAt = now.plus(CLAIM_TTL);
		String token = "lease_" + UUID.randomUUID().toString().substring(0, 8);
		String itemKey = LeaseKeys.itemKey(scope, itemId);
		String tokenKey = LeaseKeys.tokenKey(token);

		Long claimed = redis.execute(CLAIM, List.of(itemKey, tokenKey),
				String.valueOf(now.toEpochMilli()),
				String.valueOf(copies),
				String.valueOf(expiresAt.toEpochMilli()),
				token,
				itemKey);

		if (claimed == null || claimed == 0) {
			log.info("copy-lease: claim refused, no copy free scope={} itemId={} copies={}", scope, itemId, copies);
			return Optional.empty();
		}
		log.info("copy-lease: claimed scope={} itemId={} copies={} expiresAt={}", scope, itemId, copies, expiresAt);
		return Optional.of(new LeaseHandle(token, scope, itemId, expiresAt));
	}

	@Override
	public Optional<LeaseHandle> acquire(String itemId) {
		// No institution scope — produces key "lease::itemId" (scope segment is empty,
		// not the string "null"). All operations on the returned handle (extend, release)
		// use handle.scope() which is null, and LeaseKeys.itemKey() converts that to ""
		// consistently, so the round-trip is always correct.
		// No internal caller uses this — it exists for Khushi's hold module, which
		// promotes a copy to a waiting reader without knowing the copy count.
		return claim(null, itemId, 1);
	}

	@Override
	public boolean extend(LeaseHandle handle, Instant until) {
		if (handle == null || handle.token() == null) {
			return false;
		}
		String itemKey = LeaseKeys.itemKey(handle.scope(), handle.itemId());
		String tokenKey = LeaseKeys.tokenKey(handle.token());
		Long extended = redis.execute(EXTEND, List.of(itemKey, tokenKey),
				String.valueOf(until.toEpochMilli()), handle.token());
		boolean succeeded = extended != null && extended == 1;
		log.info("copy-lease: extend scope={} itemId={} until={} succeeded={}", handle.scope(), handle.itemId(),
				until, succeeded);
		return succeeded;
	}

	@Override
	public void release(LeaseHandle handle) {
		if (handle != null) {
			log.info("copy-lease: release scope={} itemId={}", handle.scope(), handle.itemId());
			release(handle.token());
		}
	}

	@Override
	public void release(String leaseId) {
		if (leaseId == null) {
			return;
		}
		// Never the token itself in the log line — see the class javadoc on why this overload
		// never has an itemId to report either: the caller (loan return, expiry sweep, borrow
		// rollback) genuinely doesn't have one, only the bearer token being released.
		Long released = redis.execute(RELEASE, List.of(LeaseKeys.tokenKey(leaseId)), leaseId);
		log.info("copy-lease: release by token, found={}", released != null && released == 1);
	}

	@Override
	public void reassign(String scope, String itemId, String fromToken, String newToken, Instant until) {
		log.info("copy-lease: reassign scope={} itemId={} until={}", scope, itemId, until);
		String itemKey = LeaseKeys.itemKey(scope, itemId);
		String oldTokenKey = LeaseKeys.tokenKey(fromToken);
		String newTokenKey = LeaseKeys.tokenKey(newToken);
		Long result = redis.execute(REASSIGN, List.of(itemKey, oldTokenKey, newTokenKey),
				fromToken, String.valueOf(until.toEpochMilli()), newToken, itemKey);
		if (result == null || result == 0) {
			// The old token was already gone (reaped or otherwise) — the interface is void so
			// the promoted reader still gets their offer, but the copy slot this call meant to
			// carry forward was NOT created. Logged at WARN rather than silently returning,
			// since discarding this used to let ZCARD silently exceed `copies` — see the
			// REASSIGN script's own comment.
			log.warn("copy-lease: reassign found no live lease for fromToken, no slot carried forward scope={} itemId={}",
					scope, itemId);
		}
	}

	/**
	 * Rebuilds one item's lease state from the DB's own truth. Writes every seed, then
	 * removes whatever Redis holds that the DB doesn't know about — but only once that
	 * entry has outlived the claim grace window. Only ever called by
	 * {@link ReconcilerService} — this is not part of the published {@code CopyLease}
	 * contract, since no caller outside the reading module has a reason to overwrite a
	 * counter rather than claim against it.
	 *
	 * <p>Rebuilding is never a wipe-and-replace: a reader mid-request between {@link #claim}
	 * and the licence write has a Redis entry with no DB row yet, and that is expected, not
	 * a defect. Deleting it early would free a slot that is about to be legitimately spent,
	 * over-lending the title. So an unmatched entry is only ever an orphan once its score is
	 * further out than a fresh claim's TTL could reach — {@code extend()} is the only other
	 * caller that pushes a score that far out, and {@code extend} only runs after the licence
	 * this seed set would already reflect.
	 *
	 * @param seeds every token that should be live right now, per the DB
	 */
	void rebuild(String scope, String itemId, List<LeaseSeed> seeds, Instant now) {
		String itemKey = LeaseKeys.itemKey(scope, itemId);
		Set<String> seeded = new HashSet<>();
		for (LeaseSeed seed : seeds) {
			if (seed.expiresAt().isAfter(now)) {
				seeded.add(seed.token());
				redis.opsForZSet().add(itemKey, seed.token(), seed.expiresAt().toEpochMilli());
				redis.opsForValue().set(LeaseKeys.tokenKey(seed.token()), itemKey,
						Duration.between(now, seed.expiresAt()));
			}
		}
		removeOrphans(itemKey, seeded, now);
	}

	/**
	 * Every item key Redis currently holds a counter for — including one with no live DB
	 * row at all, which is exactly the case {@link #rebuild} must still visit to purge a
	 * lease nothing backs any more.
	 */
	// SCAN, not KEYS: KEYS blocks Redis's single command thread for the whole keyspace walk, so
	// everything else hitting Redis while it runs — claims, extends, availability reads — risks
	// tripping the 200ms command timeout. This runs on a schedule, so that was a recurring burst
	// of failures, not a one-off. See queue audit, 2026-09-20.
	Set<LeaseKeys.Parsed> knownItems() {
		Set<LeaseKeys.Parsed> items = new HashSet<>();
		try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(LeaseKeys.ALL_KEYS_PATTERN).count(500).build())) {
			cursor.forEachRemaining(key -> {
				if (!key.startsWith(LeaseKeys.TOKEN_KEY_PREFIX)) {
					items.add(LeaseKeys.parseItemKey(key));
				}
			});
		}
		return items;
	}

	private void removeOrphans(String itemKey, Set<String> seeded, Instant now) {
		Set<TypedTuple<String>> current = redis.opsForZSet().rangeWithScores(itemKey, 0, -1);
		if (current == null) {
			return;
		}
		Instant orphanCutoff = now.plus(CLAIM_TTL);
		int removed = 0;
		for (TypedTuple<String> member : current) {
			String token = member.getValue();
			Double score = member.getScore();
			if (token == null || score == null || seeded.contains(token)) {
				continue;
			}
			if (Instant.ofEpochMilli(score.longValue()).isAfter(orphanCutoff)) {
				redis.opsForZSet().remove(itemKey, token);
				redis.delete(LeaseKeys.tokenKey(token));
				removed++;
			}
		}
		if (removed > 0) {
			log.info("copy-lease: rebuild removed {} orphaned lease(s) for itemKey={}", removed, itemKey);
		}
	}

	@Override
	public int available(String scope, String itemId, int copies) {
		String itemKey = LeaseKeys.itemKey(scope, itemId);
		redis.opsForZSet().removeRangeByScore(itemKey, Double.NEGATIVE_INFINITY, clock.instant().toEpochMilli());
		Long used = redis.opsForZSet().zCard(itemKey);
		return Math.max(copies - (used == null ? 0 : used.intValue()), 0);
	}
}
