package com.tf.reader.hold.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HoldTest {

    @Test
    void queuedBuildsAFreshQueuedHoldWithAGeneratedPublicId() {
        Instant now = Instant.parse("2026-08-17T09:00:00Z");

        Hold hold = Hold.queued("user_a", "inst_1", "item_1", 7L, now);

        assertThat(hold.getId()).as("Mongo assigns this, not us").isNull();
        assertThat(hold.getHoldId()).startsWith("hold_");
        assertThat(hold.getUserId()).isEqualTo("user_a");
        assertThat(hold.getScope()).isEqualTo("inst_1");
        assertThat(hold.getItemId()).isEqualTo("item_1");
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.QUEUED);
        assertThat(hold.getTicket()).isEqualTo(7L);
        assertThat(hold.getPlacedAt()).isEqualTo(now);
        assertThat(hold.getOffer()).as("null unless actually OFFERED").isNull();
    }

    @Test
    void twoFreshHoldsNeverShareAPublicId() {
        Instant now = Instant.now();
        Hold a = Hold.queued("user_a", "inst_1", "item_1", 1L, now);
        Hold b = Hold.queued("user_b", "inst_1", "item_1", 2L, now);

        assertThat(a.getHoldId()).isNotEqualTo(b.getHoldId());
    }
}
