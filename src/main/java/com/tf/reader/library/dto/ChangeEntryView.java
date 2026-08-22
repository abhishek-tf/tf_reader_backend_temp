package com.tf.reader.library.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tf.reader.library.api.ChangeReason;
import com.tf.reader.library.entity.ChangeLogEntry;

/**
 * One entry of the sync feed, on the wire.
 *
 * <p>{@code sequence} is ordering, not a count — the client stores the last one it saw and sends it
 * back as {@code since}.
 *
 * <p>{@code loanId} and {@code holdId} are omitted rather than sent as null, because which one is
 * present is how the client knows what kind of change it is holding.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangeEntryView(
		long sequence,
		ChangeReason reason,
		String itemId,
		String loanId,
		String holdId,
		Instant occurredAt) {

	public static ChangeEntryView from(ChangeLogEntry entry) {
		return new ChangeEntryView(
				entry.getSequence(),
				entry.getReason(),
				entry.getItemId(),
				entry.getLoanId(),
				entry.getHoldId(),
				entry.getOccurredAt());
	}

}
