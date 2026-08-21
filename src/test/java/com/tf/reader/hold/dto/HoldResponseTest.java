package com.tf.reader.hold.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tf.reader.hold.api.HoldView;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HoldResponseTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void ofCopiesEveryFieldFromTheViewAndStampsTheServerClock() {
        Instant placedAt = Instant.parse("2026-08-17T09:00:00Z");
        Instant now = Instant.parse("2026-08-17T09:05:00Z");
        var view = new HoldView("hold_1", "item_1", "QUEUED", 2, 5, 14, placedAt, null);

        var response = HoldResponse.of(view, now);

        assertThat(response.holdId()).isEqualTo("hold_1");
        assertThat(response.position()).isEqualTo(2);
        assertThat(response.estimatedWaitDays()).isEqualTo(14);
        assertThat(response.serverTime()).isEqualTo(now);
    }

    @Test
    void estimatedWaitDaysIsOmittedFromTheJsonWhenNull() throws Exception {
        // Contract convention: absent, not null. Test for presence, not for
        // the value being the literal JSON `null`.
        var view = new HoldView("hold_1", "item_1", "OFFERED", 0, 1, null, Instant.now(), null);
        var response = HoldResponse.of(view, Instant.now());

        String json = mapper.writeValueAsString(response);

        assertThat(json).doesNotContain("estimatedWaitDays");
        assertThat(json).doesNotContain("\"offer\"");
    }
}
