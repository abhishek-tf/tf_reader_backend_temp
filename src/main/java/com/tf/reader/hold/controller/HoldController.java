package com.tf.reader.hold.controller;

import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.hold.dto.HoldRequest;
import com.tf.reader.hold.dto.HoldResponse;
import com.tf.reader.hold.service.QueueService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

// HTTP endpoints for placing and reading holds. Leave and accept land in a
// later commit, once QueueService grows those lifecycle transitions.
@RestController
@RequestMapping("/api/v1/holds")
public class HoldController {

    private final QueueService queue;
    private final Clock clock;

    public HoldController(QueueService queue, Clock clock) {
        this.queue = queue;
        this.clock = clock;
    }

    // Join the queue. 201 for a new place, 200 for one they already had.
    @PostMapping
    public ResponseEntity<HoldResponse> place(@AuthenticationPrincipal CurrentUser me,
                                               @Valid @RequestBody HoldRequest body) {
        var placed = queue.join(me, body.itemId());
        var payload = HoldResponse.of(placed.view(), clock.instant());
        return placed.created()
                ? ResponseEntity.status(HttpStatus.CREATED).body(payload)
                : ResponseEntity.ok(payload);
    }

    // This reader's holds, with LIVE positions — computed here, on read.
    @GetMapping
    public List<HoldResponse> mine(@AuthenticationPrincipal CurrentUser me) {
        Instant now = clock.instant();
        return queue.holdsFor(me.userId()).stream().map(v -> HoldResponse.of(v, now)).toList();
    }
}
