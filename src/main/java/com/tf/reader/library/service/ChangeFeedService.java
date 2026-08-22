package com.tf.reader.library.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.library.dto.ChangeEntryView;
import com.tf.reader.library.dto.ChangesResponse;
import com.tf.reader.library.entity.ChangeLogEntry;
import com.tf.reader.library.repository.ChangeLogRepository;

/**
 * Serves ordered change entries since a cursor.
 */
@Service
public class ChangeFeedService {

	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 100;

	private final ChangeLogRepository changeLog;
	private final Clock clock;

	public ChangeFeedService(ChangeLogRepository changeLog, Clock clock) {
		this.changeLog = changeLog;
		this.clock = clock;
	}

	/**
	 * A page of the reader's feed, oldest first.
	 *
	 * <p><b>{@code hasMore} costs one extra row, not a count.</b> We ask for {@code size + 1} and
	 * report whether it came back; counting the feed would mean reading all of it to answer a
	 * question the client never asks.
	 *
	 * @throws ApiException 400 if the cursor is above this reader's high-water mark
	 */
	public ChangesResponse changesSince(String userId, ChangeCursor since, int size) {
		List<ChangeLogEntry> fetched = changeLog.findByUserIdAndSequenceGreaterThanOrderBySequenceAsc(
				userId, since.sequence(), Limit.of(size + 1));

		boolean hasMore = fetched.size() > size;
		List<ChangeLogEntry> page = hasMore ? fetched.subList(0, size) : fetched;

		if (page.isEmpty()) {
			// Only checked here, so the common case costs one query rather than two: a non-empty
			// page is proof enough that the cursor was behind the high-water mark.
			refuseACursorFromTheFuture(userId, since);

			// An empty page echoes the cursor the client sent rather than resetting to the
			// beginning. Returning BEGINNING would make a caught-up client replay its whole history
			// on every poll, which looks like a slow screen rather than the bug it is.
			return new ChangesResponse(List.of(), since.value(), false, now());
		}

		return new ChangesResponse(
				page.stream().map(ChangeEntryView::from).toList(),
				ChangeCursor.of(page.get(page.size() - 1).getSequence()).value(),
				hasMore,
				now());
	}

	/**
	 * Where the reader's feed stands right now.
	 *
	 * <p>Zero for a reader with no history, which {@link ChangeCursor#parse} accepts back and reads
	 * as "from the beginning" — so a brand new reader is not a special case for the client.
	 */
	public ChangeCursor currentCursor(String userId) {
		return changeLog.findFirstByUserIdOrderBySequenceDesc(userId)
				.map(entry -> ChangeCursor.of(entry.getSequence()))
				.orElse(ChangeCursor.BEGINNING);
	}

	/**
	 * A cursor past the end of the feed is refused, not answered with an empty page.
	 *
	 * <p>This is the whole reason task 10 exists. An empty page reads to the device as "you are up
	 * to date", so a client that somehow got ahead — a restored backup, a bad cursor, a clock-derived
	 * value from an older build — would sit there believing it had seen everything, and would keep
	 * the keys for revoked titles forever with nothing logged anywhere. A 400 is loud, and the app's
	 * recovery is to resync from the beginning.
	 *
	 * <p>Raised as {@code VALIDATION_FAILED} because {@code BAD_CURSOR} does not exist in the shared
	 * enum. Adding a code needs the Contracts Gate; this is the note to raise it there.
	 */
	private void refuseACursorFromTheFuture(String userId, ChangeCursor since) {
		long highWaterMark = currentCursor(userId).sequence();
		if (since.sequence() > highWaterMark) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED,
					"That cursor is ahead of this reader's change feed. Resync from the beginning.");
		}
	}

	/** Whole seconds, per the wire convention, and from the injected clock so tests can move it. */
	Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.SECONDS);
	}

	/**
	 * Validates the requested page size against the documented bounds.
	 *
	 * <p>Rejected rather than clamped: a client that asked for 500 and silently received 100 reads
	 * the short page as "that is everything", and stops paging with changes still unread.
	 */
	public static int requirePageSize(Integer size) {
		if (size == null) {
			return DEFAULT_SIZE;
		}
		if (size < 1 || size > MAX_SIZE) {
			throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
		}
		return size;
	}

}
