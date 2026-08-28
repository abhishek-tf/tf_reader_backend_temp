package com.tf.reader.library.entity;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import com.tf.reader.library.api.ChangeReason;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A {@code ChangeLog.record()} call that failed to write, kept durably so {@code
 * OutboxReplayService} has something real to retry. Never read by anything the reader sees —
 * {@code GET /api/v1/library} and the change feed both read {@code changeLog} directly, never
 * this collection.
 */
@Document(collection = "changeLogOutbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEntry {

	@Id
	private String id;

	private String userId;
	private ChangeReason reason;
	private String itemId;
	private String loanId;
	private String holdId;
	private Instant occurredAt;

	/** When the original write first failed. Replay drains oldest first. */
	private Instant failedAt;

	/** How many replay attempts have run against this entry, for observability only. */
	private int attempts;
}
