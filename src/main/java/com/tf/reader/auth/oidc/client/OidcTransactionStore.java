package com.tf.reader.auth.oidc.client;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Server-side store of in-flight OIDC sign-ins, keyed by the {@code state} parameter.
 *
 * <p><b>Why this exists.</b> Three things have to survive a redirect through a third party
 * without being handed to the client, and this is where they wait:
 *
 * <ul>
 * <li>the <b>institution</b>, because we run one OIDC integration for every institution and
 * nothing the provider returns says which one was chosen. A client-supplied institutionId on the
 * way back is exactly how one user reads another institution's content</li>
 * <li>the <b>state</b>, so a callback can be proved to belong to a sign-in we started, in this
 * backend, recently. Without it, anyone who can make a browser issue a request to our callback
 * can start a session - the OIDC form of CSRF</li>
 * <li>the <b>nonce</b>, so the ID token can be proved to have been minted for <em>this</em>
 * authorization request. State binds the browser to the redirect; the nonce binds the token to
 * it, which is what stops a token replayed from another sign-in being accepted</li>
 * </ul>
 *
 * <p>A transaction is <b>single use</b>: consuming it removes it, so a replayed callback - the
 * same code and state posted twice - cannot start a second session even if the provider were
 * willing to exchange the code again.
 *
 * <p><b>Keyed by state rather than by id</b>, because state is what the callback carries. The id
 * the client was given never comes back and is not a credential.
 *
 * <p>In memory on purpose, exactly like {@code AuthTransactionStore}: a sign-in in progress is
 * worth nothing after a restart and the user simply signs in again. This is the piece that would
 * become Redis if we ever ran more than one instance.
 */
@Component
public class OidcTransactionStore {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	/**
	 * Above this many in-flight sign-ins, opening one first sweeps the expired ones.
	 *
	 * <p>Threshold rather than every call: the sweep is O(n), and a caller opening a transaction
	 * should not pay for a scan while the map is small. It matters because {@code /auth/oidc/start}
	 * is public and unauthenticated, so anyone can reach it; without a sweep every call would
	 * leave an entry behind for good.
	 */
	static final int EVICT_ABOVE = 256;

	private final Map<String, OidcTransaction> byState = new ConcurrentHashMap<>();
	private final Clock clock;
	private final OidcProperties properties;

	public OidcTransactionStore(Clock clock, OidcProperties properties) {
		this.clock = clock;
		this.properties = properties;
	}

	/** Opens a transaction for an institution, with a fresh state and nonce. */
	public OidcTransaction open(String institutionId) {
		if (byState.size() >= EVICT_ABOVE) {
			evictExpired();
		}
		Instant now = clock.instant();
		OidcTransaction transaction = new OidcTransaction(
				randomValue("oidcTxn_"),
				institutionId,
				// 24 bytes of SecureRandom each. State and nonce must be unguessable for their
				// checks to mean anything: a predictable state is a state an attacker can pre-empt.
				randomValue(""),
				randomValue(""),
				now,
				now.plus(properties.transactionTtl()));

		byState.put(transaction.state(), transaction);
		return transaction;
	}

	/**
	 * Consumes the transaction a callback refers to, removing it so it cannot be used twice.
	 *
	 * <p><b>This single lookup is the state check.</b> There is no separate "compare the state"
	 * step to forget: a state that was not issued here, or was issued too long ago, or has already
	 * been redeemed, simply finds nothing - and a sign-in with no transaction cannot proceed,
	 * because there would be no institution and no nonce to check the token against.
	 *
	 * @return the transaction, or empty if the state is unknown, already used or expired
	 */
	public Optional<OidcTransaction> consume(String state) {
		if (state == null || state.isBlank()) {
			return Optional.empty();
		}
		OidcTransaction transaction = byState.remove(state);
		if (transaction == null || transaction.hasExpiredAt(clock.instant())) {
			return Optional.empty();
		}
		return Optional.of(transaction);
	}

	/** In-flight sign-ins currently held. Package-private: for tests, not for callers. */
	int inFlight() {
		return byState.size();
	}

	/** Drops transactions nobody came back for, so an idle process does not accumulate them. */
	public int evictExpired() {
		Instant now = clock.instant();
		int before = byState.size();
		byState.values().removeIf(transaction -> transaction.hasExpiredAt(now));
		return before - byState.size();
	}

	private static String randomValue(String prefix) {
		byte[] bytes = new byte[24];
		RANDOM.nextBytes(bytes);
		return prefix + ENCODER.encodeToString(bytes);
	}
}
