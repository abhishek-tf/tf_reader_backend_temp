package com.tf.reader.library.service;

import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Hands out the next change-feed sequence for a reader.
 *
 * <p><b>One atomic {@code $inc}, never read-then-write.</b> Reading the current value and writing
 * value-plus-one lets two writes for the same reader observe the same number, and the unique
 * {@code reader_sequence} index then rejects the second — so the read-then-write version does not
 * merely race, it <em>loses a change</em> under exactly the concurrency it exists to survive.
 * {@code findAndModify} does the read, the increment and the write in one round trip that MongoDB
 * serialises per document.
 *
 * <p>The first call for a reader upserts to {@code 1}, so {@code 0} is never a real sequence. That
 * is what lets {@code 0} mean "from the beginning" in a cursor, and "not recorded" as a return
 * value, without either being ambiguous.
 */
@Component
public class ReaderSequenceAllocator {

	/** One document per reader, keyed by userId. Small, hot, and never queried in bulk. */
	static final String COLLECTION = "changeSeq";

	private static final String FIELD = "sequence";

	private final MongoTemplate mongo;

	public ReaderSequenceAllocator(MongoTemplate mongo) {
		this.mongo = mongo;
	}

	public long next(String userId) {
		Document allocated = mongo.findAndModify(
				Query.query(Criteria.where("_id").is(userId)),
				new Update().inc(FIELD, 1L),
				FindAndModifyOptions.options().returnNew(true).upsert(true),
				Document.class,
				COLLECTION);

		if (allocated == null) {
			// returnNew(true) with upsert(true) always yields the post-increment document, so this
			// is unreachable rather than a case to quietly default a zero into.
			throw new IllegalStateException("sequence allocation returned nothing for " + userId);
		}
		return allocated.getLong(FIELD);
	}

}
