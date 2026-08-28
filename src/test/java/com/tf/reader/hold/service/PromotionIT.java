package com.tf.reader.hold.service;

import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.hold.HoldContainerTest;
import com.tf.reader.hold.entity.Hold;
import com.tf.reader.hold.entity.HoldStatus;
import com.tf.reader.hold.entity.Offer;
import com.tf.reader.hold.repository.HoldRepository;
import com.tf.reader.hold.repository.HoldWrites;
import com.tf.reader.library.api.ChangeReason;
import com.tf.reader.library.repository.ChangeLogRepository;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// join() runs through the real EntitlementQuery, which needs a real
// catalogue item and a 2-copy entitlement seeded for it - seeded via
// MongoTemplate rather than importing catalogue's entity/repository
// classes (hold may only depend on catalogue's api/ package, even from a
// test). oneItemsResultDoesNotBlockAnother uses its own item ids and never
// calls join(), so it needs none of this.
class PromotionIT extends HoldContainerTest {

    private static final String SCOPE = "inst_1";
    private static final String ITEM = "item_1";
    private static final String COLLECTION = "col_promotion_it";

    @Autowired
    QueueService queue;
    @Autowired
    PromotionService promotion;
    @Autowired
    OfferSweeper sweeper;
    @Autowired
    HoldRepository holds;
    @Autowired
    HoldWrites writes;
    @Autowired
    CopyLease lease;
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
                .append("publisherId", "pub_promotion_it")
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
    @DisplayName("with two copies, promoting three times in a row reaches exactly two different readers")
    void twoCopiesReachTwoDifferentReaders() {
        queue.join(user("a"), ITEM);
        queue.join(user("b"), ITEM);
        queue.join(user("c"), ITEM);

        assertThat(promotion.promoteNext(SCOPE, ITEM, null)).isTrue();
        assertThat(promotion.promoteNext(SCOPE, ITEM, null)).isTrue();
        assertThat(promotion.promoteNext(SCOPE, ITEM, null))
                .as("the lease is full — a third promotion must not double-book either copy")
                .isFalse();

        List<Hold> offered = holds.findByScopeAndItemIdAndStatusOrderByTicketAsc(SCOPE, ITEM, HoldStatus.OFFERED);
        assertThat(offered).hasSize(2);
        assertThat(offered).extracting(Hold::getUserId).containsExactly("user_a", "user_b");
        assertThat(queue.holdsFor("user_c").get(0).status()).isEqualTo("QUEUED");

        assertThat(changeLog.findFirstByUserIdOrderBySequenceDesc("user_a").orElseThrow().getReason())
                .isEqualTo(ChangeReason.HOLD_PROMOTED);
        assertThat(changeLog.findFirstByUserIdOrderBySequenceDesc("user_b").orElseThrow().getReason())
                .isEqualTo(ChangeReason.HOLD_PROMOTED);
    }

    @Test
    @DisplayName("reassigning a copy never lets it read as free during the handover")
    void reassignNeverDropsTheCount() throws Exception {
        queue.join(user("a"), ITEM);
        queue.join(user("b"), ITEM);
        promotion.promoteNext(SCOPE, ITEM, null); // user_a now holds the only copy this test grants
        String heldToken = holds.findByScopeAndItemIdAndUserId(SCOPE, ITEM, "user_a")
                .orElseThrow().getOffer().getLeaseToken();

        var sawFree = new AtomicBoolean(false);
        var stop = new AtomicBoolean(false);
        var watcher = Executors.newSingleThreadExecutor();
        watcher.submit(() -> {
            while (!stop.get()) {
                if (lease.available(SCOPE, ITEM, 2) == 2) {
                    sawFree.set(true);
                }
            }
        });

        promotion.promoteNext(SCOPE, ITEM, heldToken); // hand it to user_b without releasing first
        stop.set(true);
        watcher.shutdown();
        watcher.awaitTermination(20, TimeUnit.SECONDS);

        assertThat(sawFree).as("the lease count must never touch zero mid-handover").isFalse();
    }

    @Test
    @DisplayName("accept and the sweep firing on the same offer admit exactly one winner")
    void acceptAndTheSweepRaceToExactlyOneWinner() throws Exception {
        Instant now = Instant.now();
        Hold hold = holds.save(Hold.queued("user_b", SCOPE, ITEM, 1, now));
        writes.offerIfQueued(hold.getHoldId(), new Offer("offer_1", now.minusSeconds(120), now.minusSeconds(1), "lease_test_1"));

        var start = new CountDownLatch(1);
        var wins = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(2);

        pool.submit(() -> {
            try {
                start.await();
                try {
                    queue.accept(user("b"), hold.getHoldId());
                    wins.incrementAndGet();
                } catch (RuntimeException expected) {
                    // OFFER_EXPIRED — the sweep won this race instead
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                sweeper.sweep();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        start.countDown();
        pool.shutdown();
        pool.awaitTermination(20, TimeUnit.SECONDS);

        assertThat(holds.findByHoldId(hold.getHoldId())).as("gone either way").isEmpty();
        assertThat(wins.get()).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a reader who misses their turn rejoins at the back, not the front")
    void aMissedTurnRejoinsAtTheBack() {
        Instant now = Instant.now();
        Hold offered = holds.save(Hold.queued("user_b", SCOPE, ITEM, 1, now));
        writes.offerIfQueued(offered.getHoldId(), new Offer("offer_1", now.minusSeconds(120), now.minusSeconds(1), null));
        queue.join(user("c"), ITEM);
        // A second queued reader, deliberately: with 2 free copies and nobody
        // holding one yet, the sweep's own promotion would otherwise take
        // user_c straight out of the queue, leaving nobody genuinely ahead
        // of user_b to actually test "rejoins at the back" against.
        queue.join(user("d"), ITEM);

        sweeper.sweep();

        assertThat(queue.holdsFor("user_b")).as("the lapsed hold is gone entirely").isEmpty();
        assertThat(changeLog.findFirstByUserIdOrderBySequenceDesc("user_b").orElseThrow().getReason())
                .isEqualTo(ChangeReason.HOLD_OFFER_EXPIRED);
        var rejoined = queue.join(user("b"), ITEM);
        assertThat(rejoined.view().position()).as("behind user_d, who's still genuinely queued").isEqualTo(2);
    }

    @Test
    @DisplayName("one lapsed offer failing to promote does not stop the sweep from processing the others")
    void oneItemsResultDoesNotBlockAnother() {
        Instant now = Instant.now();

        Hold offerA = holds.save(Hold.queued("user_a", SCOPE, "item_a", 1, now));
        writes.offerIfQueued(offerA.getHoldId(), new Offer("offer_a", now.minusSeconds(120), now.minusSeconds(1), null));

        Hold offerB = holds.save(Hold.queued("user_b", SCOPE, "item_b", 1, now));
        writes.offerIfQueued(offerB.getHoldId(), new Offer("offer_b", now.minusSeconds(120), now.minusSeconds(1), null));

        sweeper.sweep();

        assertThat(holds.findByHoldId(offerA.getHoldId())).isEmpty();
        assertThat(holds.findByHoldId(offerB.getHoldId())).isEmpty();
    }
}
