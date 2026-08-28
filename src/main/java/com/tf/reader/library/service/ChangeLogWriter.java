package com.tf.reader.library.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;

import com.tf.reader.library.api.ChangeLog;
import com.tf.reader.library.api.ChangeRecord;
import com.tf.reader.library.entity.ChangeLogEntry;
import com.tf.reader.library.entity.OutboxEntry;
import com.tf.reader.library.repository.ChangeLogOutboxRepository;
import com.tf.reader.library.repository.ChangeLogRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * The only implementation of {@link ChangeLog}, and the only writer of the change log.
 */
@Slf4j
@Service
public class ChangeLogWriter implements ChangeLog {

	/** What {@link #record} returns when nothing was written. Never a real sequence. */
	private static final long NOT_RECORDED = 0L;

	private final ChangeLogRepository changeLog;
	private final ChangeLogOutboxRepository outbox;
	private final ReaderSequenceAllocator sequences;
	private final Clock clock;

	public ChangeLogWriter(ChangeLogRepository changeLog, ChangeLogOutboxRepository outbox,
			ReaderSequenceAllocator sequences, Clock clock) {
		this.changeLog = changeLog;
		this.outbox = outbox;
		this.sequences = sequences;
		this.clock = clock;
	}

	@Override
	public long record(ChangeRecord change) {
		try {
			long sequence = sequences.next(change.userId());

			changeLog.save(ChangeLogEntry.builder()
					.userId(change.userId())
					.sequence(sequence)
					.reason(change.reason())
					.itemId(change.itemId())
					.loanId(change.loanId())
					.holdId(change.holdId())
					// Whole seconds, because that is what goes on the wire. Truncating here rather
					// than at serialisation keeps the stored value equal to the value the app sees.
					.occurredAt(change.occurredAt().truncatedTo(ChronoUnit.SECONDS))
					.build());

			return sequence;
		}
		catch (RuntimeException failed) {
			// Deliberately swallowed, and this is the whole point of task 8. This runs inside the
			// caller's transaction — Shashank's return path, Khushi's promotion — and a reader whose
			// return actually succeeded must never be told "returning this book failed" because our
			// feed write did not. Rethrowing would also put the person debugging it in the wrong
			// lane entirely.
			//
			// Safe to swallow because the feed is not the source of truth: GET /api/v1/library reads
			// the real loans and holds, so a lost entry is a delayed screen rather than a wrong one.
			// Logged at error because it is still a defect — a lost ENTITLEMENT_REVOKED is the one
			// case the full read does not paper over quickly.
			log.error("change log write failed, reader={} reason={} item={} — entry lost",
					change.userId(), change.reason(), change.itemId(), failed);
			saveToOutbox(change);
			return NOT_RECORDED;
		}
	}

	// A second, independent failure here (Mongo itself unreachable, not just this one write
	// racing an index) is only ever logged, never rethrown — the same reason record() itself
	// never throws. OutboxReplayService is what gives this entry a real chance later; this
	// method existing at all is what gives OutboxReplayService anything to find.
	private void saveToOutbox(ChangeRecord change) {
		try {
			outbox.save(OutboxEntry.builder()
					.userId(change.userId())
					.reason(change.reason())
					.itemId(change.itemId())
					.loanId(change.loanId())
					.holdId(change.holdId())
					.occurredAt(change.occurredAt().truncatedTo(ChronoUnit.SECONDS))
					.failedAt(clock.instant())
					.attempts(0)
					.build());
		}
		catch (RuntimeException outboxAlsoFailed) {
			log.error("change log outbox write also failed, reader={} reason={} item={} — entry"
					+ " permanently lost", change.userId(), change.reason(), change.itemId(), outboxAlsoFailed);
		}
	}

}
