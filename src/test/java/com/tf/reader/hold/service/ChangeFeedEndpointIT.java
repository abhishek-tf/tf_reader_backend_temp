package com.tf.reader.hold.service;

import com.jayway.jsonpath.JsonPath;
import com.tf.reader.ContainerisedInfrastructure;
import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.auth.token.JwtTokenService;
import com.tf.reader.hold.HoldContainerTest;
import com.tf.reader.hold.entity.Hold;
import com.tf.reader.hold.entity.Offer;
import com.tf.reader.hold.repository.HoldRepository;
import com.tf.reader.hold.repository.HoldWrites;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Proves the four HOLD_* events don't just land in the changeLog collection - they actually
// come back out of the real, published GET /api/v1/changes endpoint (Haripriyaa's own
// module), through the real security chain, for the real reader who caused them. Everything
// that writes an event runs through its real HTTP or service path; nothing here seeds a
// ChangeLogEntry directly.
@AutoConfigureMockMvc
class ChangeFeedEndpointIT extends HoldContainerTest {

    private static final String SCOPE = "inst_1";
    private static final String ITEM = "item_change_feed_it";
    private static final String COLLECTION = "col_change_feed_it";
    private static final String PUBLISHER = "pub_change_feed_it";

    @Autowired
    QueueService queue;
    @Autowired
    OfferSweeper sweeper;
    @Autowired
    HoldRepository holds;
    @Autowired
    HoldWrites writes;
    @Autowired
    RedisConnectionFactory redisConnectionFactory;
    @Autowired
    MongoTemplate mongo;
    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void seedCatalogueAndEntitlement() {
        mongo.remove(Query.query(Criteria.where("_id").is(ITEM)), "catalogueItems");
        mongo.remove(Query.query(Criteria.where("institutionId").is(SCOPE)), "entitlements");
        mongo.remove(Query.query(Criteria.where("_id").is(SCOPE)), "institutions");
        mongo.remove(Query.query(Criteria.where("_id").is(PUBLISHER)), "publishers");

        // EntitlementQueryImpl.check() looks the institution up first and reads a suspended one
        // exactly like an unknown one - so without this row, borrow() would fail NOT_FOUND before
        // ever reaching the entitlement this test seeds. code must be non-null: it's uniquely
        // indexed, and a second null would collide with any other seeded institution.
        mongo.save(new Document()
                .append("_id", SCOPE)
                .append("code", "CHANGE_FEED_IT")
                .append("name", "Change Feed IT institution")
                .append("status", "ACTIVE"), "institutions");

        // check() also denies NO_ENTITLEMENT for a book whose publisher isn't ACTIVE, checked
        // before any grant lookup - so the publisher has to exist and be active too.
        mongo.save(new Document()
                .append("_id", PUBLISHER)
                .append("code", "CHANGE_FEED_IT")
                .append("name", "Change Feed IT publisher")
                .append("status", "ACTIVE"), "publishers");

        mongo.save(new Document()
                .append("_id", ITEM)
                .append("status", "PUBLISHED")
                .append("contentState", "READY")
                .append("accessTier", "ELITE")
                .append("publisherId", PUBLISHER)
                .append("collectionIds", List.of(COLLECTION)), "catalogueItems");

        mongo.save(new Document()
                .append("institutionId", SCOPE)
                .append("scopeType", "COLLECTION")
                .append("scopeId", COLLECTION)
                .append("copies", 1)
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
        mongo.remove(Query.query(Criteria.where("_id").is(SCOPE)), "institutions");
        mongo.remove(Query.query(Criteria.where("_id").is(PUBLISHER)), "publishers");
    }

    private static CurrentUser user(String suffix) {
        return new CurrentUser("user_" + suffix, UserType.INSTITUTION, SCOPE, List.of(), List.of());
    }

    // Suite-wide test JWT secret, already set in src/test/resources/application.properties.
    private static String token(String userId) {
        TnfUser caller = new TnfUser(userId, UserType.INSTITUTION, SCOPE, List.of("MEMBER"), List.of(COLLECTION));
        return JwtTokenService.forTest(ContainerisedInfrastructure.JWT_SECRET, Duration.ofHours(1), Clock.systemUTC())
                .issue(caller).token();
    }

    @Test
    @DisplayName("HOLD_PLACED then HOLD_CANCELLED both come back from the real feed after join then leave")
    void joinThenLeaveAppearOnTheRealFeed() throws Exception {
        var placed = queue.join(user("feed_a"), ITEM);
        queue.leave(user("feed_a"), placed.view().holdId());

        assertThat(reasonsFor("user_feed_a")).containsSubsequence("HOLD_PLACED", "HOLD_CANCELLED");
    }

    @Test
    @DisplayName("HOLD_PROMOTED comes back from the real feed after a real loan return frees the copy")
    void promotionAppearsOnTheRealFeed() throws Exception {
        String loan0 = borrow("user_feed_0");
        queue.join(user("feed_b"), ITEM);
        returnLoan(loan0, "user_feed_0");

        assertThat(reasonsFor("user_feed_b")).contains("HOLD_PROMOTED");
    }

    @Test
    @DisplayName("HOLD_OFFER_EXPIRED comes back from the real feed after the real sweep catches a lapsed offer")
    void offerExpiryAppearsOnTheRealFeed() throws Exception {
        Instant now = Instant.now();
        Hold offered = holds.save(Hold.queued("user_feed_c", SCOPE, ITEM, 1, now));
        writes.offerIfQueued(offered.getHoldId(),
                new Offer("offer_feed_c", now.minusSeconds(120), now.minusSeconds(1), null));

        sweeper.sweep();

        assertThat(reasonsFor("user_feed_c")).contains("HOLD_OFFER_EXPIRED");
    }

    private List<String> reasonsFor(String userId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/changes").header("Authorization", "Bearer " + token(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.changes[*].reason");
    }

    private String borrow(String userId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/loans")
                        .header("Authorization", "Bearer " + token(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + ITEM + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.loanId");
    }

    private void returnLoan(String loanId, String userId) throws Exception {
        mockMvc.perform(post("/api/v1/loans/" + loanId + "/return")
                        .header("Authorization", "Bearer " + token(userId)))
                .andExpect(status().isOk());
    }
}
