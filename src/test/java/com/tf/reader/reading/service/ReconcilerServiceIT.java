package com.tf.reader.reading.service;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.tf.reader.ContainerisedInfrastructure;
import com.tf.reader.hold.entity.Hold;
import com.tf.reader.hold.entity.Offer;
import com.tf.reader.hold.repository.HoldRepository;
import com.tf.reader.hold.repository.HoldWrites;
import com.tf.reader.loan.entity.LicenceModel;
import com.tf.reader.loan.entity.Loan;
import com.tf.reader.loan.entity.LoanStatus;
import com.tf.reader.loan.repository.LoanRepository;
import com.tf.reader.reading.api.CopyLease;
import com.tf.reader.reading.api.LeaseHandle;

import static org.assertj.core.api.Assertions.assertThat;

// Real Mongo and real Redis (Testcontainers), not mocks — the point of the reconciler is
// that it re-derives Redis from what Mongo actually holds, so both sides have to be real for
// a green test to mean anything.
@SpringBootTest
class ReconcilerServiceIT extends ContainerisedInfrastructure {

	private static final String SCOPE = "inst_recon";
	private static final String ITEM = "item_recon";

	@Autowired
	ReconcilerService reconciler;
	@Autowired
	CopyLease lease;
	@Autowired
	LoanRepository loans;
	@Autowired
	HoldRepository holds;
	@Autowired
	HoldWrites holdWrites;
	@Autowired
	RedisConnectionFactory redisConnectionFactory;
	@Autowired
	StringRedisTemplate redis;

	@AfterEach
	void cleanUp() {
		redisConnectionFactory.getConnection().serverCommands().flushAll();
		loans.deleteAll();
		holds.deleteAll();
	}

	@Test
	void redisWipeMidFlightLosesNoActiveLoanAndNoLiveOffer() {
		loans.save(Loan.builder()
				.loanId("loan_recon_1").userId("user_1").itemId(ITEM).institutionId(SCOPE)
				.licenceModel(LicenceModel.ELITE).status(LoanStatus.ACTIVE).canPersist(false)
				.borrowedAt(Instant.now().minusSeconds(60))
				.dueAt(Instant.now().plusSeconds(600))
				.leaseId("lease_from_loan").build());

		Hold queued = holds.save(Hold.queued("user_2", SCOPE, ITEM, 1L, Instant.now()));
		holdWrites.offerIfQueued(queued.getHoldId(),
				new Offer("offer_1", Instant.now(), Instant.now().plusSeconds(600), "lease_from_offer"));

		// The Redis wipe: everything the reconciler is meant to survive.
		redisConnectionFactory.getConnection().serverCommands().flushAll();
		assertThat(lease.available(SCOPE, ITEM, 2)).isEqualTo(2); // wiped clean, as if it never happened

		reconciler.reconcileAll();

		assertThat(lease.available(SCOPE, ITEM, 2)).isZero(); // both slots accounted for again
		assertThat(lease.claim(SCOPE, ITEM, 2)).isEmpty(); // no third slot invented by the rebuild
	}

	@Test
	void reconcileDropsAGenuinelyStaleEntryNoLongerBackedByAnyLoanOrOffer() {
		// A hand-written Redis entry with a score far beyond the claim grace window and no
		// backing Mongo row — the only way such an entry could exist for real is a loan or
		// offer that has since gone away without releasing its lease.
		String staleToken = "lease_stale";
		Instant farFuture = Instant.now().plusSeconds(3600);
		redis.opsForZSet().add(LeaseKeys.itemKey(SCOPE, ITEM), staleToken, farFuture.toEpochMilli());

		reconciler.reconcileAll();

		assertThat(lease.available(SCOPE, ITEM, 1)).isEqualTo(1); // the stale slot was reclaimed
	}

	@Test
	void reconcileNeverEvictsAClaimStillInsideItsGraceWindowEvenWithNoDbRowYet() {
		// Simulates the exact race the reconciler must not win: a reader between claim() and
		// the licence write, where the DB seed genuinely doesn't exist yet.
		LeaseHandle inFlight = lease.claim(SCOPE, ITEM, 1).orElseThrow();

		reconciler.reconcileAll();

		assertThat(lease.extend(inFlight, Instant.now().plusSeconds(600)))
				.as("a fresh claim must survive a reconcile that runs before its licence is written")
				.isTrue();
	}
}
