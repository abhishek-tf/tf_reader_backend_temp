package com.tf.reader.library;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.tf.reader.ContainerisedInfrastructure;
import com.tf.reader.library.api.ChangeReason;
import com.tf.reader.library.entity.OutboxEntry;
import com.tf.reader.library.repository.ChangeLogOutboxRepository;
import com.tf.reader.library.repository.ChangeLogRepository;
import com.tf.reader.library.service.OutboxReplayService;

import static org.assertj.core.api.Assertions.assertThat;

// Real Mongo, not mocks — proves an entry that failed to write once, and was durably kept in
// changeLogOutbox for exactly that reason, actually reaches the real changeLog on the next
// replay and is removed from the outbox afterwards.
@SpringBootTest
@ActiveProfiles("local")
class OutboxReplayServiceIT extends ContainerisedInfrastructure {

	@Autowired
	ChangeLogOutboxRepository outbox;
	@Autowired
	ChangeLogRepository changeLog;
	@Autowired
	OutboxReplayService replay;

	@AfterEach
	void cleanUp() {
		outbox.deleteAll();
	}

	@Test
	void replayWritesAStuckEntryToTheRealChangeLogAndRemovesItFromTheOutbox() {
		OutboxEntry stuck = outbox.save(OutboxEntry.builder()
				.userId("usr_outbox_it")
				.reason(ChangeReason.HOLD_PROMOTED)
				.itemId("item_42")
				.holdId("hold_seed_c3")
				.occurredAt(Instant.parse("2026-08-24T14:30:05Z"))
				.failedAt(Instant.parse("2026-08-24T14:30:06Z"))
				.attempts(0)
				.build());

		replay.replayAll();

		assertThat(outbox.findById(stuck.getId())).isEmpty();
		assertThat(changeLog.findFirstByUserIdOrderBySequenceDesc("usr_outbox_it"))
				.isPresent()
				.get()
				.satisfies(entry -> {
					assertThat(entry.getReason()).isEqualTo(ChangeReason.HOLD_PROMOTED);
					assertThat(entry.getHoldId()).isEqualTo("hold_seed_c3");
				});
	}
}
