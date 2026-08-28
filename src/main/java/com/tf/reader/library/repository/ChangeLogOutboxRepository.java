package com.tf.reader.library.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.tf.reader.library.entity.OutboxEntry;

/**
 * The {@code changeLogOutbox} collection — failed {@code ChangeLog.record()} calls, waiting for
 * {@code OutboxReplayService} to retry them.
 */
public interface ChangeLogOutboxRepository extends MongoRepository<OutboxEntry, String> {

	/** Oldest failure first, so a jammed entry doesn't starve the ones behind it forever. */
	List<OutboxEntry> findAllByOrderByFailedAtAsc();
}
