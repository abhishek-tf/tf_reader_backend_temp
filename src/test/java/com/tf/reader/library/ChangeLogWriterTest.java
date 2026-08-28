package com.tf.reader.library;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import com.tf.reader.library.api.ChangeReason;
import com.tf.reader.library.api.ChangeRecord;
import com.tf.reader.library.entity.ChangeLogEntry;
import com.tf.reader.library.repository.ChangeLogOutboxRepository;
import com.tf.reader.library.repository.ChangeLogRepository;
import com.tf.reader.library.service.ChangeLogWriter;
import com.tf.reader.library.service.ReaderSequenceAllocator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChangeLogWriterTest {

	private static final String READER = "user_9c2";
	private static final Instant AT = Instant.parse("2026-08-20T10:00:00Z");

	private final ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
	private final ChangeLogOutboxRepository outbox = mock(ChangeLogOutboxRepository.class);
	private final ReaderSequenceAllocator sequences = mock(ReaderSequenceAllocator.class);
	private final Clock clock = Clock.fixed(AT, ZoneOffset.UTC);
	private final ChangeLogWriter writer = new ChangeLogWriter(changeLog, outbox, sequences, clock);

	@Test
	@DisplayName("the sequence comes from the allocator and is returned to the caller")
	void allocatesAndReturnsTheSequence() {
		when(sequences.next(READER)).thenReturn(1190L);

		long sequence = writer.record(
				ChangeRecord.forHold(READER, ChangeReason.HOLD_PROMOTED, "item_42", "hold_5d1", AT));

		assertThat(sequence).isEqualTo(1190L);
		assertThat(saved().getSequence()).isEqualTo(1190L);
	}

	@Test
	@DisplayName("occurredAt is stored to whole seconds, matching what goes on the wire")
	void truncatesToWholeSeconds() {
		when(sequences.next(READER)).thenReturn(1L);

		writer.record(ChangeRecord.forLoan(READER, ChangeReason.LOAN_CREATED, "item_42", "loan_7c1",
				Instant.parse("2026-08-20T10:00:00.987654321Z")));

		assertThat(saved().getOccurredAt()).isEqualTo(Instant.parse("2026-08-20T10:00:00Z"));
	}

	@Test
	@DisplayName("every field of the record reaches the document")
	void mapsTheWholeRecord() {
		when(sequences.next(READER)).thenReturn(7L);

		writer.record(ChangeRecord.forLoan(READER, ChangeReason.LOAN_RETURNED, "item_42",
				"loan_7c1", AT));

		ChangeLogEntry entry = saved();
		assertThat(entry.getUserId()).isEqualTo(READER);
		assertThat(entry.getReason()).isEqualTo(ChangeReason.LOAN_RETURNED);
		assertThat(entry.getItemId()).isEqualTo("item_42");
		assertThat(entry.getLoanId()).isEqualTo("loan_7c1");
		assertThat(entry.getHoldId()).isNull();
	}

	@Test
	@DisplayName("a failed save does not throw into the caller's transaction")
	void swallowsAFailedSave() {
		// Shashank's return path calls this. If it threw, a reader whose return actually succeeded
		// would be told "returning this book failed", and the person debugging would start here
		// rather than in the loan lane.
		when(sequences.next(READER)).thenReturn(5L);
		when(changeLog.save(any())).thenThrow(new DuplicateKeyException("reader_sequence"));

		ChangeRecord change =
				ChangeRecord.forLoan(READER, ChangeReason.LOAN_RETURNED, "item_42", "loan_7c1", AT);

		assertThatCode(() -> writer.record(change)).doesNotThrowAnyException();
		assertThat(writer.record(change)).isZero();

		verify(outbox, atLeastOnce()).save(any());
	}

	@Test
	@DisplayName("a failed allocation does not throw either, and writes nothing")
	void swallowsAFailedAllocation() {
		when(sequences.next(READER)).thenThrow(new IllegalStateException("mongo unreachable"));

		ChangeRecord change =
				ChangeRecord.forHold(READER, ChangeReason.HOLD_PLACED, "item_77", "hold_5d1", AT);

		assertThatCode(() -> writer.record(change)).doesNotThrowAnyException();
		assertThat(writer.record(change)).isZero();
		verify(changeLog, never()).save(any());
	}

	@Test
	@DisplayName("zero means not recorded, and is never a real sequence")
	void zeroIsTheNotRecordedSignal() {
		// Allocation starts at 1, so a caller can read 0 as "no entry" without ambiguity — the same
		// value a cursor uses for "from the beginning".
		when(sequences.next(READER)).thenReturn(1L);

		assertThat(writer.record(
				ChangeRecord.forLoan(READER, ChangeReason.LOAN_CREATED, "item_42", "loan_7c1", AT)))
				.isNotZero()
				.isEqualTo(1L);
	}

	private ChangeLogEntry saved() {
		ArgumentCaptor<ChangeLogEntry> captor = ArgumentCaptor.forClass(ChangeLogEntry.class);
		verify(changeLog, atLeastOnce()).save(captor.capture());
		return captor.getValue();
	}

}
