package com.tf.reader.common.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * The audit trail as the console reads it: every filter in the contract is optional and they
 * combine, and the newest record always comes first.
 */
@Repository
public class AuditLogSearchRepository {

	private final MongoTemplate mongo;

	public AuditLogSearchRepository(MongoTemplate mongo) {
		this.mongo = mongo;
	}

	public record Results(List<AuditLog> items, long total) {
	}

	public Results search(String entityType, String entityId, String actorId, AuditLog.Action action, Instant from,
			Instant to, int page, int size) {
		List<Criteria> parts = new ArrayList<>();

		if (hasValue(entityType)) {
			parts.add(Criteria.where("entityType").is(entityType));
		}
		if (hasValue(entityId)) {
			parts.add(Criteria.where("entityId").is(entityId));
		}
		if (hasValue(actorId)) {
			parts.add(Criteria.where("actorId").is(actorId));
		}
		if (action != null) {
			parts.add(Criteria.where("action").is(action));
		}
		if (from != null || to != null) {
			parts.add(atRange(from, to));
		}

		Query query = parts.isEmpty()
				? new Query()
				: new Query(new Criteria().andOperator(parts.toArray(new Criteria[0])));
		query.with(Sort.by(Sort.Direction.DESC, "at"));

		long total = mongo.count(Query.of(query).limit(0).skip(0), AuditLog.class);

		query.skip((long) page * size).limit(size);
		return new Results(mongo.find(query, AuditLog.class), total);
	}

	/** Both bounds go on one criterion: two separate where("at") clauses would overwrite each other. */
	private static Criteria atRange(Instant from, Instant to) {
		Criteria at = Criteria.where("at");
		if (from != null) {
			at = at.gte(from);
		}
		if (to != null) {
			at = at.lte(to);
		}
		return at;
	}

	private static boolean hasValue(String value) {
		return value != null && !value.isBlank();
	}

}
