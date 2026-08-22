package com.tf.reader.library;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.library.api.ChangeReason;
import com.tf.reader.library.dto.ChangesResponse;
import com.tf.reader.library.entity.ChangeLogEntry;
import com.tf.reader.library.repository.ChangeLogRepository;
import com.tf.reader.library.service.ChangeCursor;
import com.tf.reader.library.service.ChangeFeedService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChangeFeedServiceTest {

	private static final String READER = "user_9c2";
	private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

	private final ChangeLogRepository changeLog = mock(ChangeLogRepository.class);
	private final ChangeFeedService feed =
			new ChangeFeedService(changeLog, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	@DisplayName("hasMore costs one extra row rather than a count of the stream")
	void asksForOneMoreThanItReturns() {
		givenPage(entry(1188), entry(1189), entry(1190));

		ChangesResponse response = feed.changesSince(READER, ChangeCursor.of(1187L), 2);

		ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
		verify(changeLog).findByUserIdAndSequenceGreaterThanOrderBySequenceAsc(
				eq(READER), eq(1187L), limit.capture());
		assertThat(limit.getValue().max()).isEqualTo(3);

		assertThat(response.hasMore()).isTrue();
		assertThat(response.changes()).extracting("sequence").containsExactly(1188L, 1189L);
	}

	@Test
	@DisplayName("a full page that is exactly the last page does not claim there is more")
	void exactPageIsNotMore() {
		givenPage(entry(1188), entry(1189));

		ChangesResponse response = feed.changesSince(READER, ChangeCursor.of(1187L), 2);

		assertThat(response.hasMore()).isFalse();
		assertThat(response.changes()).hasSize(2);
	}

	@Test
	@DisplayName("nextCursor is the last entry actually returned, not the last one fetched")
	void nextCursorIsTheLastVisibleEntry() {
		givenPage(entry(1188), entry(1189), entry(1190));

		// 1190 was read only to answer hasMore. Handing it back as the cursor would skip it.
		assertThat(feed.changesSince(READER, ChangeCursor.of(1187L), 2).nextCursor())
				.isEqualTo("1189");
	}

	@Test
	@DisplayName("changes come back oldest first, so applying them in order converges")
	void oldestFirst() {
		givenPage(entry(1188), entry(1189), entry(1190));

		assertThat(feed.changesSince(READER, ChangeCursor.BEGINNING, 20).changes())
				.extracting("sequence").containsExactly(1188L, 1189L, 1190L);
	}

	@Test
	@DisplayName("a caught-up client gets its own cursor back, not a rewind to the beginning")
	void emptyPageEchoesTheCursor() {
		givenPage();
		givenHighWaterMark(1189L);

		ChangesResponse response = feed.changesSince(READER, ChangeCursor.of(1189L), 20);

		// Returning "0" here would replay the reader's entire history on every poll.
		assertThat(response.nextCursor()).isEqualTo("1189");
		assertThat(response.changes()).isEmpty();
		assertThat(response.hasMore()).isFalse();
	}

	@Test
	@DisplayName("a cursor above the high-water mark is refused, never answered with an empty page")
	void refusesACursorFromTheFuture() {
		// The bug this prevents: an empty page reads as "you are up to date", so a client that got
		// ahead keeps revoked keys forever with nothing logged anywhere.
		givenPage();
		givenHighWaterMark(1189L);

		assertThatThrownBy(() -> feed.changesSince(READER, ChangeCursor.of(9999L), 20))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> assertThat(((ApiException) thrown).getCode())
						.isEqualTo(ErrorCode.VALIDATION_FAILED));
	}

	@Test
	@DisplayName("a new reader asking from the beginning gets an empty page, not a refusal")
	void newReaderIsNotFromTheFuture() {
		givenPage();
		when(changeLog.findFirstByUserIdOrderBySequenceDesc(READER)).thenReturn(Optional.empty());

		ChangesResponse response = feed.changesSince(READER, ChangeCursor.BEGINNING, 20);

		assertThat(response.changes()).isEmpty();
		assertThat(response.nextCursor()).isEqualTo("0");
	}

	@Test
	@DisplayName("a non-empty page costs one query, not two")
	void doesNotCheckTheHighWaterMarkWhenItDoesNotHaveTo() {
		givenPage(entry(1188));

		feed.changesSince(READER, ChangeCursor.of(1187L), 20);

		// A page that came back is proof the cursor was behind the mark.
		verify(changeLog, org.mockito.Mockito.never()).findFirstByUserIdOrderBySequenceDesc(anyString());
	}

	@Test
	@DisplayName("serverTime comes from the clock, to whole seconds")
	void serverTimeIsFromTheClock() {
		givenPage(entry(1188));

		assertThat(feed.changesSince(READER, ChangeCursor.BEGINNING, 20).serverTime()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("the cursor for a reader with no history is the beginning, not an error")
	void currentCursorForNewReader() {
		when(changeLog.findFirstByUserIdOrderBySequenceDesc(READER)).thenReturn(Optional.empty());

		assertThat(feed.currentCursor(READER)).isEqualTo(ChangeCursor.BEGINNING);
	}

	@Test
	@DisplayName("the cursor for an existing reader is their newest sequence")
	void currentCursorIsTheNewestSequence() {
		givenHighWaterMark(1189L);

		assertThat(feed.currentCursor(READER)).isEqualTo(ChangeCursor.of(1189L));
	}

	@Test
	@DisplayName("an out-of-range size is rejected rather than clamped")
	void pageSizeBounds() {
		assertThat(ChangeFeedService.requirePageSize(null)).isEqualTo(ChangeFeedService.DEFAULT_SIZE);
		assertThat(ChangeFeedService.requirePageSize(1)).isEqualTo(1);
		assertThat(ChangeFeedService.requirePageSize(100)).isEqualTo(100);

		// A client that asked for 500 and silently got 100 reads the short page as "that is
		// everything" and stops paging with changes still unread.
		assertThatThrownBy(() -> ChangeFeedService.requirePageSize(101))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ChangeFeedService.requirePageSize(0))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private void givenPage(ChangeLogEntry... entries) {
		when(changeLog.findByUserIdAndSequenceGreaterThanOrderBySequenceAsc(
				anyString(), anyLong(), any(Limit.class))).thenReturn(List.of(entries));
	}

	private void givenHighWaterMark(long sequence) {
		when(changeLog.findFirstByUserIdOrderBySequenceDesc(READER))
				.thenReturn(Optional.of(entry(sequence)));
	}

	private static ChangeLogEntry entry(long sequence) {
		return ChangeLogEntry.builder()
				.userId(READER)
				.sequence(sequence)
				.reason(ChangeReason.HOLD_PROMOTED)
				.itemId("item_42")
				.holdId("hold_5d1")
				.occurredAt(NOW)
				.build();
	}

}
