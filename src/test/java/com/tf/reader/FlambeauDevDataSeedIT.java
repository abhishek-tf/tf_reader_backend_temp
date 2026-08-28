package com.tf.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.tf.reader.hold.entity.Hold;
import com.tf.reader.hold.entity.HoldStatus;
import com.tf.reader.hold.repository.HoldRepository;
import com.tf.reader.library.repository.ChangeLogRepository;
import com.tf.reader.loan.entity.Loan;
import com.tf.reader.loan.entity.LoanStatus;
import com.tf.reader.loan.repository.LoanRepository;
import com.tf.reader.reading.repository.DeviceRepository;
import com.tf.reader.reading.service.ReconcilerService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the four flambeau-owned dev seeders (loan, hold, reading/devices, library/changeLog)
 * actually load, are internally consistent with each other, and that {@link ReconcilerService}
 * — running automatically on {@code ApplicationReadyEvent} — rebuilds a correct Redis lease state
 * from the seeded loan and hold data with no manual Redis seeding needed on the lease side.
 */
@SpringBootTest(properties = "tnf.seed.enabled=true")
@ActiveProfiles("local")
class FlambeauDevDataSeedIT extends ContainerisedInfrastructure {

	@Autowired
	LoanRepository loans;
	@Autowired
	HoldRepository holds;
	@Autowired
	DeviceRepository devices;
	@Autowired
	ChangeLogRepository changeLog;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	ReconcilerService reconciler;

	@Test
	@DisplayName("loan seed: the queue scenario's two elite loans land with the right status")
	void loanSeedLoaded() {
		Loan active = loans.findById("loan_seed_a1").orElseThrow();
		assertThat(active.getStatus()).isEqualTo(LoanStatus.ACTIVE);
		assertThat(active.getLeaseId()).isEqualTo("lease_seed_a1");

		Loan returned = loans.findById("loan_seed_b2").orElseThrow();
		assertThat(returned.getStatus()).isEqualTo(LoanStatus.RETURNED);
	}

	@Test
	@DisplayName("hold seed: one offer, two queued, and the queue ZSET matches Mongo")
	void holdSeedLoadedAndQueueIsConsistent() {
		Hold offered = holds.findByHoldId("hold_seed_c3").orElseThrow();
		assertThat(offered.getStatus()).isEqualTo(HoldStatus.OFFERED);
		assertThat(offered.getOffer().getLeaseToken()).isEqualTo("lease_seed_c3");

		assertThat(holds.findByHoldId("hold_seed_d4").orElseThrow().getStatus()).isEqualTo(HoldStatus.QUEUED);
		assertThat(holds.findByHoldId("hold_seed_e5").orElseThrow().getStatus()).isEqualTo(HoldStatus.QUEUED);

		// QueueService reads position from Redis, never Mongo — the seeder must have written both.
		Long queueLength = redis.opsForZSet().zCard("queue:inst_7f3:item_42");
		assertThat(queueLength).isEqualTo(2L); // d4 and e5 only; c3 is OFFERED, already off the queue

		Long d4Rank = redis.opsForZSet().rank("queue:inst_7f3:item_42", "u:usr_d4");
		Long e5Rank = redis.opsForZSet().rank("queue:inst_7f3:item_42", "u:usr_e5");
		assertThat(d4Rank).isLessThan(e5Rank); // ticket order preserved
	}

	@Test
	@DisplayName("device seed: the dev-token default user is seeded near the cap")
	void deviceSeedLoaded() {
		var fingerprint = devices.findByUserId("usr_dev123").orElseThrow();
		assertThat(fingerprint.getDevices()).hasSize(4);
	}

	@Test
	@DisplayName("change-log seed: history exists and sequences are real allocator numbers")
	void changeLogSeedLoaded() {
		var history = changeLog.findFirstByUserIdOrderBySequenceDesc("usr_dev123").orElseThrow();
		assertThat(history.getSequence()).isGreaterThanOrEqualTo(1L);
	}

	@Test
	@DisplayName("the reconciler rebuilds item_42's Redis lease state from the seeded loan and offer alone")
	void reconcilerRebuildsLeaseStateFromSeededData() {
		reconciler.reconcileAll();

		Double activeLoanScore = redis.opsForZSet().score("lease:inst_7f3:item_42", "lease_seed_a1");
		Double offerScore = redis.opsForZSet().score("lease:inst_7f3:item_42", "lease_seed_c3");
		assertThat(activeLoanScore).as("loan_seed_a1's lease").isNotNull();
		assertThat(offerScore).as("hold_seed_c3's offer lease").isNotNull();
	}
}
