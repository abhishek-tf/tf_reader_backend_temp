package com.tf.reader.loan.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process idempotency store for mutating loan endpoints (D-027).
 *
 * <p>A client that sends the same {@code Idempotency-Key} header within the TTL gets the cached
 * response back without any database write. Keys are scoped by {@code userId} — one user cannot
 * replay another's response.
 *
 * <p><b>In-memory by design for this prototype.</b> A Redis-backed store would survive restarts
 * and scale across nodes, but the team convention prohibits new dependencies without a cohort
 * conversation and the TTL (5 min) is shorter than any realistic retry window. The most dangerous
 * double-tap — two concurrent return calls — is exactly what this covers.
 *
 * @param <T> the cached response type
 */
public class IdempotencyStore<T> {

	private record Entry<T>(T value, Instant expiresAt) {}

	private final ConcurrentHashMap<String, Entry<T>> store = new ConcurrentHashMap<>();
	private final Duration ttl;
	private final Clock clock;

	public IdempotencyStore(Duration ttl, Clock clock) {
		this.ttl = ttl;
		this.clock = clock;
	}

	/**
	 * Returns the cached response for {@code (userId, key)}, or empty if not seen or expired.
	 */
	public Optional<T> get(String userId, String key) {
		Entry<T> entry = store.get(storeKey(userId, key));
		if (entry == null) {
			return Optional.empty();
		}
		if (clock.instant().isAfter(entry.expiresAt())) {
			store.remove(storeKey(userId, key));
			return Optional.empty();
		}
		return Optional.of(entry.value());
	}

	/**
	 * Caches the response for {@code (userId, key)} for the TTL duration.
	 */
	public void put(String userId, String key, T value) {
		store.put(storeKey(userId, key), new Entry<>(value, clock.instant().plus(ttl)));
	}

	private static String storeKey(String userId, String key) {
		return userId + ":" + key;
	}
}
