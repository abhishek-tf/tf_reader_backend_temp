package com.tf.reader.hold.controller;

import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.hold.api.HoldView;
import com.tf.reader.hold.dto.HoldRequest;
import com.tf.reader.hold.service.QueueService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

// Plain unit tests, not MockMvc — there's no real Spring Security wiring in
// this repo yet (SecurityConfig is still an empty stub), so these call the
// controller's methods directly rather than going through HTTP.
class HoldControllerTest {

    private final QueueService queue = mock(QueueService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-17T09:00:00Z"), ZoneOffset.UTC);
    private final HoldController controller = new HoldController(queue, clock);
    private final CurrentUser me = new CurrentUser("user_a", UserType.INSTITUTION, "inst_1", List.of(), List.of());

    @Test
    void placeReturns201ForANewHold() {
        var view = new HoldView("hold_1", "item_1", "QUEUED", 1, 1, 14, Instant.now(), null);
        when(queue.join(me, "item_1")).thenReturn(new QueueService.Placed(view, true));

        var response = controller.place(me, new HoldRequest("item_1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().holdId()).isEqualTo("hold_1");
        assertThat(response.getBody().serverTime()).isEqualTo(clock.instant());
    }

    @Test
    void placeReturns200WhenAlreadyQueued() {
        var view = new HoldView("hold_1", "item_1", "QUEUED", 1, 1, 14, Instant.now(), null);
        when(queue.join(me, "item_1")).thenReturn(new QueueService.Placed(view, false));

        var response = controller.place(me, new HoldRequest("item_1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void mineWrapsEveryHoldWithTheServerClock() {
        var a = new HoldView("hold_1", "item_1", "QUEUED", 1, 1, 14, Instant.now(), null);
        var b = new HoldView("hold_2", "item_2", "QUEUED", 2, 3, 28, Instant.now(), null);
        when(queue.holdsFor("user_a")).thenReturn(List.of(a, b));

        var responses = controller.mine(me);

        assertThat(responses).hasSize(2);
        assertThat(responses).allSatisfy(r -> assertThat(r.serverTime()).isEqualTo(clock.instant()));
    }
}
