package com.tf.reader.auth.oidc.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The store that makes state and nonce mean something.
 *
 * <p>Every check the OIDC callback performs on "is this a sign-in we started?" is really a
 * property of this class, so this is where they are pinned: unguessable values, single use, a
 * lifetime, and an institution that never leaves the server.
 */
class OidcTransactionStoreTest {

	private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

	private final MutableClock clock = new MutableClock(NOW);
	private final OidcTransactionStore store =
			new OidcTransactionStore(clock, OidcProperties.forIssuer("https://issuer"));

	@Test
	void openingATransactionRecordsTheInstitutionWithAStateAndANonce() {
		OidcTransaction transaction = store.open("inst_ucl");

		assertThat(transaction.institutionId()).isEqualTo("inst_ucl");
		assertThat(transaction.id()).startsWith("oidcTxn_");
		assertThat(transaction.state()).isNotBlank();
		assertThat(transaction.nonce()).isNotBlank();
		assertThat(transaction.createdAt()).isEqualTo(NOW);
		assertThat(transaction.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
	}

	@Test
	void theIdTheStateAndTheNonceAreThreeDifferentValues() {
		// The id is handed to the client; the state goes to the provider; the nonce goes into the
		// token. If any two were the same value, a party who saw one would hold another.
		OidcTransaction transaction = store.open("inst_ucl");

		assertThat(transaction.state()).isNotEqualTo(transaction.id());
		assertThat(transaction.nonce()).isNotEqualTo(transaction.state());
		assertThat(transaction.nonce()).isNotEqualTo(transaction.id());
	}

	@Test
	void everyStateAndNonceIsUnique() {
		// A predictable state is a state an attacker can pre-empt, and a predictable nonce is one
		// they can have a token minted for. 24 bytes of SecureRandom each; this catches the day
		// somebody swaps in a counter or a timestamp.
		Set<String> values = new HashSet<>();
		for (int i = 0; i < 500; i++) {
			OidcTransaction transaction = store.open("inst_ucl");
			values.add(transaction.state());
			values.add(transaction.nonce());
			values.add(transaction.id());
		}
		assertThat(values).hasSize(1500);
	}

	@Test
	void aTransactionIsFoundByItsStateAndNotByItsId() {
		// The callback carries state. The id never comes back and is not a credential.
		OidcTransaction transaction = store.open("inst_ucl");

		assertThat(store.consume(transaction.id())).isEmpty();
		assertThat(store.consume(transaction.state())).contains(transaction);
	}

	@Test
	void aTransactionCanBeConsumedOnlyOnce() {
		// Single use, which is what stops a replayed callback starting a second session.
		OidcTransaction transaction = store.open("inst_7f3");

		assertThat(store.consume(transaction.state())).isPresent();
		assertThat(store.consume(transaction.state())).isEmpty();
	}

	@Test
	void anExpiredTransactionIsRefused() {
		OidcTransaction transaction = store.open("inst_7f3");
		clock.advance(Duration.ofMinutes(11));

		assertThat(store.consume(transaction.state())).isEmpty();
	}

	@Test
	void aTransactionInsideItsLifetimeIsAccepted() {
		// Guards the guard: a store that refused everything would pass the expiry test above.
		OidcTransaction transaction = store.open("inst_7f3");
		clock.advance(Duration.ofMinutes(9));

		assertThat(store.consume(transaction.state())).isPresent();
	}

	@Test
	void expiryIsInclusiveAtTheBoundary() {
		OidcTransaction transaction = store.open("inst_7f3");
		clock.advance(Duration.ofMinutes(10));

		assertThat(store.consume(transaction.state())).isEmpty();
	}

	@Test
	void anUnknownOrEmptyStateIsRefusedRatherThanThrowing() {
		// These arrive straight off a query string, so they are attacker-controlled by definition.
		// A null here must be an empty Optional, not a NullPointerException turning a 401 into a
		// 500 with a stack trace.
		assertThat(store.consume("never-issued")).isEmpty();
		assertThat(store.consume("")).isEmpty();
		assertThat(store.consume("   ")).isEmpty();
		assertThat(store.consume(null)).isEmpty();
	}

	@Test
	void theLifetimeIsConfigurable() {
		OidcTransactionStore shortLived = new OidcTransactionStore(clock,
				new OidcProperties(null, null, "https://issuer", null, null, null, null, null,
						Duration.ofMinutes(2), null));

		OidcTransaction transaction = shortLived.open("inst_ucl");

		assertThat(transaction.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
	}

	@Test
	void abandonedTransactionsAreSweptSoAnAnonymousCallerCannotGrowTheHeap() {
		// POST /auth/oidc/start is public, so anyone can reach open(). Without a sweep every call
		// would leave an entry behind for good. Nothing else evicts: there is no scheduler here,
		// exactly as in AuthTransactionStore.
		for (int i = 0; i < OidcTransactionStore.EVICT_ABOVE; i++) {
			store.open("inst_ucl");
		}
		assertThat(store.inFlight()).isEqualTo(OidcTransactionStore.EVICT_ABOVE);

		clock.advance(Duration.ofMinutes(11));
		store.open("inst_ucl");

		assertThat(store.inFlight()).isEqualTo(1);
	}

	@Test
	void sweepingNeverDropsATransactionSomebodyIsStillUsing() {
		for (int i = 0; i < OidcTransactionStore.EVICT_ABOVE; i++) {
			store.open("inst_ucl");
		}
		clock.advance(Duration.ofMinutes(11));

		OidcTransaction current = store.open("inst_7f3");

		assertThat(store.consume(current.state())).isPresent();
	}

	/** A clock a test can move, so expiry is asserted rather than waited for. */
	private static final class MutableClock extends Clock {

		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		void advance(Duration amount) {
			this.instant = this.instant.plus(amount);
		}

		@Override
		public Instant instant() {
			return this.instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}
}
