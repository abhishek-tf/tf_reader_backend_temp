package com.tf.reader.hold.service;

import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.hold.HoldContainerTest;
import com.tf.reader.hold.api.AvailabilityQuery;
import com.tf.reader.hold.repository.HoldRepository;
import com.tf.reader.reading.api.CopyLease;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// join() runs through the real EntitlementQuery here, so this seeds a real
// catalogue item and a real copy-limited entitlement itself via MongoTemplate
// rather than importing catalogue's entity/repository classes directly -
// hold may only depend on catalogue's api/ package, never its internals,
// even from a test.
class AvailabilityIT extends HoldContainerTest {

    private static final String SCOPE = "inst_1";
    private static final String ITEM = "item_1";
    private static final String COLLECTION = "col_availability_it";

    @Autowired
    AvailabilityQuery availability;
    @Autowired
    CopyLease lease;
    @Autowired
    QueueService queue;
    @Autowired
    HoldRepository holds;
    @Autowired
    RedisConnectionFactory redisConnectionFactory;
    @Autowired
    MongoTemplate mongo;

    @BeforeEach
    void seedCatalogueAndEntitlement() {
        // Clean before inserting, not only after - a container reused across
        // runs (Testcontainers reuse) must not depend on the previous run's
        // own cleanup having actually run.
        mongo.remove(Query.query(Criteria.where("_id").is(ITEM)), "catalogueItems");
        mongo.remove(Query.query(Criteria.where("institutionId").is(SCOPE)), "entitlements");

        mongo.save(new Document()
                .append("_id", ITEM)
                .append("status", "PUBLISHED")
                .append("contentState", "READY")
                .append("accessTier", "ELITE")
                .append("publisherId", "pub_availability_it")
                .append("collectionIds", List.of(COLLECTION)), "catalogueItems");

        mongo.save(new Document()
                .append("institutionId", SCOPE)
                .append("scopeType", "COLLECTION")
                .append("scopeId", COLLECTION)
                .append("copies", 2)
                .append("loanPeriodDays", 14)
                .append("validFrom", LocalDate.now().minusDays(1))
                .append("validTo", LocalDate.now().plusDays(30))
                .append("status", "ACTIVE")
                .append("version", 0L), "entitlements");
    }

    @AfterEach
    void cleanUp() {
        holds.deleteAll();
        redisConnectionFactory.getConnection().serverCommands().flushAll();
        mongo.remove(Query.query(Criteria.where("_id").is(ITEM)), "catalogueItems");
        mongo.remove(Query.query(Criteria.where("institutionId").is(SCOPE)), "entitlements");
    }

    private static CurrentUser user(String suffix) {
        return new CurrentUser("user_" + suffix, UserType.INSTITUTION, SCOPE, List.of(), List.of());
    }

    @Test
    @DisplayName("available and queueLength reflect real leases and a real queue, not a guess")
    void reflectsRealLeasesAndARealQueue() {
        lease.claim(SCOPE, ITEM, 2);
        queue.join(user("a"), ITEM);
        queue.join(user("b"), ITEM);

        var snapshot = availability.forItem(SCOPE, ITEM, 2);

        assertThat(snapshot.available()).isEqualTo(1);
        assertThat(snapshot.queueLength()).isEqualTo(2);
    }

    @Test
    @DisplayName("a title with no copy limit omits available, it never zeroes it")
    void noCopyLimitOmitsAvailable() {
        var snapshot = availability.forItem(SCOPE, ITEM, null);

        assertThat(snapshot.available()).isNull();
        assertThat(snapshot.queueLength()).isNull();
    }

    @Test
    @DisplayName("answers inside the 50ms budget the contract promises")
    void answersInsideTheBudget() {
        lease.claim(SCOPE, ITEM, 5);
        availability.forItem(SCOPE, ITEM, 5); // warm up

        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            availability.forItem(SCOPE, ITEM, 5);
        }
        long avgMs = (System.nanoTime() - start) / 100 / 1_000_000;

        assertThat(avgMs).isLessThan(50);
    }
}
