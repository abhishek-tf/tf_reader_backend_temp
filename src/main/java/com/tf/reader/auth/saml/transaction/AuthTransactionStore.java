package com.tf.reader.auth.saml.transaction;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Server-side store of in-flight sign-ins, keyed by an opaque transaction id.
 *
 * <p><b>Why this exists.</b> We run one SAML integration for every institution, so the SAML
 * response itself says nothing about which institution the user chose. That choice has to
 * survive a redirect through a third party without being handed to the client, because a
 * client-supplied institutionId on the way back is exactly how one user reads another
 * institution's content. The id travels as RelayState; the institution stays here.
 *
 * <p>A transaction is <b>single use</b>: consuming it removes it, so a replayed RelayState
 * cannot start a second session.
 *
 * <p>In memory on purpose - a sign-in in progress is worth nothing after a restart, and the
 * user simply signs in again. This is the one piece that would become Redis if we ever ran
 * more than one instance.
 */
@Component
public class AuthTransactionStore {

	/** Long enough for a human to work through the mock IdP form, short enough to be useless later. */
	static final Duration LIFETIME = Duration.ofMinutes(10);

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private final Map<String, AuthTransaction> transactions = new ConcurrentHashMap<>();
	private final Clock clock;

	public AuthTransactionStore(Clock clock) {
		this.clock = clock;
	}

	/**
	 * Above this many in-flight sign-ins, opening one first sweeps the expired ones.
	 *
	 * <p>Threshold rather than every call: the sweep is O(n), and a caller opening a transaction
	 * should not pay for a scan while the map is small.
	 */
	static final int EVICT_ABOVE = 256;

	/** Opens a transaction for an institution and returns it. */
	public AuthTransaction open(String institutionId) {
		// POST /auth/saml/start is public and unauthenticated, so anyone can reach this line.
		// Without a sweep, every call would leave a map entry behind for good and an anonymous
		// caller could grow the heap until the process died. Nothing else evicts: there is no
		// scheduler in this application, and adding one for a self-contained store would make the
		// store depend on configuration elsewhere.
		if (transactions.size() >= EVICT_ABOVE) {
			evictExpired();
		}
		Instant now = clock.instant();
		AuthTransaction transaction =
				new AuthTransaction(newId(), institutionId, now, now.plus(LIFETIME));
		transactions.put(transaction.id(), transaction);
		return transaction;
	}

	/** In-flight sign-ins currently held. Package-private: for tests, not for callers. */
	int inFlight() {
		return transactions.size();
	}

	/**
	 * Consumes a transaction, removing it so it cannot be used twice.
	 *
	 * @return the transaction, or empty if the id is unknown, already used or expired
	 */
	public Optional<AuthTransaction> consume(String id) {
		if (id == null || id.isBlank()) {
			return Optional.empty();
		}
		AuthTransaction transaction = transactions.remove(id);
		if (transaction == null || transaction.hasExpiredAt(clock.instant())) {
			return Optional.empty();
		}
		return Optional.of(transaction);
	}

	/** Drops transactions nobody came back for, so an idle process does not accumulate them. */
	public int evictExpired() {
		Instant now = clock.instant();
		int before = transactions.size();
		transactions.values().removeIf(transaction -> transaction.hasExpiredAt(now));
		return before - transactions.size();
	}

	private String newId() {
		byte[] bytes = new byte[24];
		RANDOM.nextBytes(bytes);
		return "authTxn_" + ENCODER.encodeToString(bytes);
	}
}
