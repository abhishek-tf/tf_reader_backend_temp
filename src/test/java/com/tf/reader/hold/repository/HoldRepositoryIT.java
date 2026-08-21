package com.tf.reader.hold.repository;

import com.tf.reader.hold.HoldContainerTest;
import com.tf.reader.hold.entity.Hold;
import com.tf.reader.hold.entity.HoldStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HoldRepositoryIT extends HoldContainerTest {

    @Autowired
    HoldRepository holds;

    @AfterEach
    void cleanUp() {
        holds.deleteAll();
    }

    @Test
    @DisplayName("a second live hold for the same reader and title is rejected")
    void theUniqueIndexIsTheGuard() {
        // The DATABASE refuses this, not application code — a stub repository
        // would happily let the index "work." This runs against a real Mongo
        // specifically so that isn't just assumed.
        holds.save(Hold.queued("user_erin", "inst_oxford", "1476-4687", 1, Instant.now()));

        assertThatThrownBy(() -> holds.save(Hold.queued("user_erin", "inst_oxford", "1476-4687", 2, Instant.now())))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("findByScopeAndItemIdAndUserId finds the caller's own row and nobody else's")
    void findsOnlyTheCallersOwnHold() {
        holds.save(Hold.queued("user_a", "inst_1", "item_1", 1, Instant.now()));
        holds.save(Hold.queued("user_b", "inst_1", "item_1", 2, Instant.now()));

        assertThat(holds.findByScopeAndItemIdAndUserId("inst_1", "item_1", "user_a")).isPresent();
        assertThat(holds.findByScopeAndItemIdAndUserId("inst_1", "item_1", "user_c")).isEmpty();
    }

    @Test
    @DisplayName("the queue order is ticket order, not insertion order")
    void queueOrderFollowsTheTicket() {
        holds.save(Hold.queued("user_c", "inst_1", "item_1", 3, Instant.now()));
        holds.save(Hold.queued("user_a", "inst_1", "item_1", 1, Instant.now()));
        holds.save(Hold.queued("user_b", "inst_1", "item_1", 2, Instant.now()));

        List<Hold> ordered = holds.findByScopeAndItemIdOrderByTicketAsc("inst_1", "item_1");
        assertThat(ordered).extracting(Hold::getUserId).containsExactly("user_a", "user_b", "user_c");
    }

    @Test
    @DisplayName("findByStatusAndOfferExpiresAtBefore only surfaces the ones actually lapsed")
    void findsOnlyLapsedOffers() {
        Instant now = Instant.now();

        Hold lapsed = Hold.queued("user_a", "inst_1", "item_1", 1, now);
        lapsed.setStatus(HoldStatus.OFFERED);
        lapsed.setOffer(new com.tf.reader.hold.entity.Offer("offer_1", now.minusSeconds(120), now.minusSeconds(60), null));
        holds.save(lapsed);

        Hold stillLive = Hold.queued("user_b", "inst_1", "item_1", 2, now);
        stillLive.setStatus(HoldStatus.OFFERED);
        stillLive.setOffer(new com.tf.reader.hold.entity.Offer("offer_2", now, now.plusSeconds(900), null));
        holds.save(stillLive);

        List<Hold> candidates = holds.findByStatusAndOfferExpiresAtBefore(HoldStatus.OFFERED, now);
        assertThat(candidates).extracting(Hold::getUserId).containsExactly("user_a");
    }
}
