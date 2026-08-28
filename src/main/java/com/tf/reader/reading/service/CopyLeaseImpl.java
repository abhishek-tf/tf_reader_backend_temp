package com.tf.reader.reading.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
	private static final DefaultRedisScript<Long> REASSIGN = new DefaultRedisScript<>("""
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
			return Optional.empty();
		}
		return Optional.of(new LeaseHandle(token, scope, itemId, expiresAt));
	}

	@Override
	public Optional<LeaseHandle> acquire(String itemId) {
		// Nothing in the codebase calls this — no caller has a scope or a copy limit to
		// give it. Kept working, as a single unscoped slot, rather than deleted, since
		// it's declared on the api/ seam and another team could already depend on it.
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
		return extended != null && extended == 1;
	}

	@Override
	public void release(LeaseHandle handle) {
		if (handle != null) {
			release(handle.token());
		}
	}

	@Override
	public void release(String leaseId) {
		if (leaseId == null) {
			return;
		}
		redis.execute(RELEASE, List.of(LeaseKeys.tokenKey(leaseId)), leaseId);
	}

	@Override
	public void reassign(String scope, String itemId, String fromToken, String newToken, Instant until) {
		String itemKey = LeaseKeys.itemKey(scope, itemId);
		String oldTokenKey = LeaseKeys.tokenKey(fromToken);
		String newTokenKey = LeaseKeys.tokenKey(newToken);
		redis.execute(REASSIGN, List.of(itemKey, oldTokenKey, newTokenKey),
				fromToken, String.valueOf(until.toEpochMilli()), newToken, itemKey);
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
	Set<LeaseKeys.Parsed> knownItems() {
		Set<String> keys = redis.keys(LeaseKeys.ALL_KEYS_PATTERN);
		if (keys == null) {
			return Set.of();
		}
		Set<LeaseKeys.Parsed> items = new HashSet<>();
		for (String key : keys) {
			if (!key.startsWith(LeaseKeys.TOKEN_KEY_PREFIX)) {
				items.add(LeaseKeys.parseItemKey(key));
			}
		}
		return items;
	}

	private void removeOrphans(String itemKey, Set<String> seeded, Instant now) {
		Set<TypedTuple<String>> current = redis.opsForZSet().rangeWithScores(itemKey, 0, -1);
		if (current == null) {
			return;
		}
		Instant orphanCutoff = now.plus(CLAIM_TTL);
		for (TypedTuple<String> member : current) {
			String token = member.getValue();
			Double score = member.getScore();
			if (token == null || score == null || seeded.contains(token)) {
				continue;
			}
			if (Instant.ofEpochMilli(score.longValue()).isAfter(orphanCutoff)) {
				redis.opsForZSet().remove(itemKey, token);
				redis.delete(LeaseKeys.tokenKey(token));
			}
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
