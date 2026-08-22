package com.tf.reader.auth.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * The store is what stops an institution being chosen by the client after authentication, so
 * its refusals matter more than its successes.
 */
class AuthTransactionStoreTest {

	private static final Instant NOW = Instant.parse("2026-08-12T14:42:00Z");

	private final MutableClock clock = new MutableClock(NOW);

	private final AuthTransactionStore store = new AuthTransactionStore(clock);

	@Test
	void openingATransactionRecordsTheInstitutionServerSide() {
		AuthTransaction transaction = store.open("inst_imperial");

		assertThat(transaction.institutionId()).isEqualTo("inst_imperial");
		assertThat(transaction.createdAt()).isEqualTo(NOW);
		assertThat(transaction.expiresAt()).isEqualTo(NOW.plus(AuthTransactionStore.LIFETIME));
	}

	@Test
	void theIdIsOpaqueAndCarriesNoInstitution() {
		// The id crosses a third party as RelayState. Anything readable in it is a leak, and
		// anything guessable in it is a way to land on somebody else's institution.
		AuthTransaction transaction = store.open("inst_imperial");

		assertThat(transaction.id()).startsWith("authTxn_").doesNotContain("imperial");
		assertThat(transaction.id()).isNotEqualTo(store.open("inst_imperial").id());
	}

	@Test
	void consumingReturnsTheInstitutionThatWasStored() {
		AuthTransaction opened = store.open("inst_dsu");

		assertThat(store.consume(opened.id()))
				.get()
				.extracting(AuthTransaction::institutionId)
				.isEqualTo("inst_dsu");
	}

	@Test
	void aTransactionIsSingleUse() {
		// Otherwise one captured RelayState signs somebody in repeatedly.
		AuthTransaction opened = store.open("inst_imperial");

		assertThat(store.consume(opened.id())).isPresent();
		assertThat(store.consume(opened.id())).isEmpty();
	}

	@Test
	void singleUseHoldsWhenTheSameRelayStateArrivesTwiceAtOnce() throws Exception {
		// Sequential single use is not the same property. A captured RelayState replayed by a
		// script arrives concurrently, and "check then remove" would let both callers through -
		// two sessions from one authentication. remove() decides the winner atomically, and this
		// is the test that would notice if it were ever rewritten as containsKey() plus remove().
		int callers = 32;
		AuthTransaction opened = store.open("inst_imperial");
		ExecutorService pool = Executors.newFixedThreadPool(callers);
		CountDownLatch startTogether = new CountDownLatch(1);
		AtomicInteger winners = new AtomicInteger();

		try {
			List<Future<?>> attempts = new ArrayList<>();
			for (int i = 0; i < callers; i++) {
				attempts.add(pool.submit(() -> {
					startTogether.await();
					if (store.consume(opened.id()).isPresent()) {
						winners.incrementAndGet();
					}
					return null;
				}));
			}
			startTogether.countDown();
			for (Future<?> attempt : attempts) {
				attempt.get(10, TimeUnit.SECONDS);
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(winners)
				.describedAs("exactly one concurrent caller may consume a transaction")
				.hasValue(1);
	}

	@Test
	void anUnknownTransactionIsRejected() {
		assertThat(store.consume("authTxn_madeUp")).isEmpty();
		assertThat(store.consume(null)).isEmpty();
		assertThat(store.consume("  ")).isEmpty();
	}

	@Test
	void anExpiredTransactionIsRejected() {
		AuthTransaction opened = store.open("inst_imperial");

		clock.advance(AuthTransactionStore.LIFETIME);

		assertThat(store.consume(opened.id())).isEmpty();
	}

	@Test
	void openingATransactionSweepsExpiredOnesOnceTheStoreIsBusy() {
		// POST /auth/saml/start is public and unauthenticated. Nothing else evicts - there is no
		// scheduler in this application - so without this sweep an anonymous caller could add one
		// permanent map entry per request until the heap ran out.
		for (int i = 0; i < AuthTransactionStore.EVICT_ABOVE; i++) {
			store.open("inst_imperial");
		}
		assertThat(store.inFlight()).isEqualTo(AuthTransactionStore.EVICT_ABOVE);

		clock.advance(AuthTransactionStore.LIFETIME);
		store.open("inst_imperial");

		assertThat(store.inFlight())
				.describedAs("the expired ones must be gone, leaving only the new transaction")
				.isEqualTo(1);
	}

	@Test
	void abandonedTransactionsAreEvicted() {
		store.open("inst_imperial");
		store.open("inst_dsu");
		AuthTransaction live = store.open("inst_xyz");

		clock.advance(AuthTransactionStore.LIFETIME.minusSeconds(1));
		AuthTransaction later = store.open("inst_imperial");
		clock.advance(Duration.ofSeconds(1));

		assertThat(store.evictExpired()).isEqualTo(3);
		assertThat(store.consume(live.id())).isEmpty();
		assertThat(store.consume(later.id())).isPresent();
	}

	/** A clock the test can move, because the store's whole contract is time-based. */
	private static final class MutableClock extends Clock {

		private Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration amount) {
			this.now = this.now.plus(amount);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}
	}
}
