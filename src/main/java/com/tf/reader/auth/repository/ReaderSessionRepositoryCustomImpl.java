package com.tf.reader.auth.repository;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.tf.reader.auth.entity.ReaderSession;

public class ReaderSessionRepositoryCustomImpl implements ReaderSessionRepositoryCustom {

	private final MongoTemplate mongoTemplate;

	public ReaderSessionRepositoryCustomImpl(MongoTemplate mongoTemplate) {
		this.mongoTemplate = mongoTemplate;
	}

	@Override
	public Optional<ReaderSession> revokeForExchange(String refreshTokenHash, String reason, Instant now) {
		// Every precondition is in the query, so claiming the row is one atomic document update. The
		// hash is unique across rows, so it identifies the session on its own.
		Query guard = new Query(where("refreshTokenHash").is(refreshTokenHash)
				.and("revokedAt").is(null)
				.and("expiresAt").gt(now));

		Update revocation = new Update().set("revokedAt", now).set("revokedReason", reason);

		// returnNew(false): the pre-update row, which still carries the identity snapshot the
		// replacement session is minted from.
		ReaderSession claimed = this.mongoTemplate.findAndModify(guard, revocation,
				FindAndModifyOptions.options().returnNew(false), ReaderSession.class);

		return Optional.ofNullable(claimed);
	}

}
