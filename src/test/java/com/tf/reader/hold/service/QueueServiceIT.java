package com.tf.reader.hold.service;

import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.hold.HoldContainerTest;
import com.tf.reader.hold.repository.HoldRepository;
import com.tf.reader.library.api.ChangeReason;
import com.tf.reader.library.repository.ChangeLogRepository;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Real Mongo, real Redis, the real EntitlementQueryImpl - which needs a
// real catalogue item and entitlement to answer entitled at all, so this
// seeds one itself via MongoTemplate rather than importing catalogue's
// entity/repository classes (hold may only depend on catalogue's api/
// package, even from a test).
class QueueServiceIT extends HoldContainerTest {

    private static final String SCOPE = "inst_1";
    private static final String ITEM = "item_1";
    private static final String COLLECTION = "col_queue_service_it";

    @Autowired
    QueueService queue;
    @Autowired
    HoldRepository holds;
    @Autowired
    RedisConnectionFactory redisConnectionFactory;
    @Autowired
    MongoTemplate mongo;
    @Autowired
    ChangeLogRepository changeLog;

    @BeforeEach
    void seedCatalogueAndEntitlement() {
        mongo.remove(Query.query(Criteria.where("_id").is(ITEM)), "catalogueItems");
        mongo.remove(Query.query(Criteria.where("institutionId").is(SCOPE)), "entitlements");

        mongo.save(new Document()
                .append("_id", ITEM)
                .append("status", "PUBLISHED")
                .append("contentState", "READY")
                .append("accessTier", "ELITE")
                .append("publisherId", "pub_queue_service_it")
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
        return new CurrentUser("user_" + suffix, UserType.INSTITUTION, "inst_1", List.of(), List.of());
    }

    @Test
    @DisplayName("joining again returns the same hold and the same position, not a new one")
    void joiningAgainIsIdempotent() {
        var first = queue.join(user("a"), "item_1");
        var second = queue.join(user("a"), "item_1");

        assertThat(first.created()).isTrue();
        assertThat(second.created()).as("200, not 201, the second time").isFalse();
        assertThat(second.view().holdId()).isEqualTo(first.view().holdId());
        assertThat(second.view().position()).isEqualTo(first.view().position());

        var entry = changeLog.findFirstByUserIdOrderBySequenceDesc("user_a").orElseThrow();
        assertThat(entry.getReason()).as("only the real join writes a change-log entry").isEqualTo(ChangeReason.HOLD_PLACED);
        assertThat(entry.getHoldId()).isEqualTo(first.view().holdId());
    }

    @Test
    @DisplayName("positions shift when somebody ahead leaves")
    void positionsShiftWhenSomebodyAheadLeaves() {
        queue.join(user("a"), "item_1");
        var b = queue.join(user("b"), "item_1");
        var c = queue.join(user("c"), "item_1");
        assertThat(c.view().position()).isEqualTo(3);

        queue.leave(user("b"), b.view().holdId());

        assertThat(queue.holdsFor("user_a").get(0).position()).as("unmoved").isEqualTo(1);
        assertThat(queue.holdsFor("user_c").get(0).position()).as("moved up one").isEqualTo(2);

        var entry = changeLog.findFirstByUserIdOrderBySequenceDesc("user_b").orElseThrow();
        assertThat(entry.getReason()).isEqualTo(ChangeReason.HOLD_CANCELLED);
        assertThat(entry.getHoldId()).isEqualTo(b.view().holdId());
    }

    @Test
    @DisplayName("cancelling twice is safe, and the hold is genuinely gone, not soft-deleted")
    void cancellingTwiceIsSafe() {
        var placed = queue.join(user("a"), "item_1");

        queue.leave(user("a"), placed.view().holdId());
        queue.leave(user("a"), placed.view().holdId()); // must not throw

        assertThat(holds.findByHoldId(placed.view().holdId())).isEmpty();
    }

    @Test
    @DisplayName("two concurrent joins for the same reader give one hold and the same position")
    void concurrentJoinsAreIdempotent() throws Exception {
        var start = new CountDownLatch(1);
        var results = new ConcurrentLinkedQueue<QueueService.Placed>();
        var pool = Executors.newFixedThreadPool(2);

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(queue.join(user("a"), "item_1"));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(20, TimeUnit.SECONDS);

        assertThat(results).hasSize(2);
        assertThat(results.stream().map(p -> p.view().holdId()).distinct().count())
                .as("both racing calls see the SAME hold, not two")
                .isEqualTo(1);
        assertThat(holds.findByUserId("user_a")).hasSize(1);
    }
}
