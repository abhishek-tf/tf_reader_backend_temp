package com.tf.reader.reading.controller;

import java.time.Clock;
import java.time.Instant;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tf.reader.hold.service.QueueReconciler;
import com.tf.reader.reading.dto.ReconcileRequest;
import com.tf.reader.reading.dto.ReconcileResponse;
import com.tf.reader.reading.service.ReconcilerService;
import lombok.RequiredArgsConstructor;

/**
 * Admin-audience ops surface ({@code aud=tf-admin}), secured by the admin filter chain in
 * {@code SecurityConfig}. Exposes on-demand reconciliation so wokay can trigger an immediate
 * rebuild when a lease extend fails rather than waiting for the scheduled pass.
 */
@RestController
@RequestMapping("/api/v1/ops")
@RequiredArgsConstructor
public class OpsController {

    private final ReconcilerService leaseReconciler;
    private final QueueReconciler queueReconciler;
    private final Clock clock;

    @PostMapping("/reconcile")
    public ReconcileResponse reconcile(@RequestBody(required = false) ReconcileRequest body) {
        Instant startedAt = clock.instant();

        String itemId = body != null ? body.itemId() : null;
        int leasesRebuilt = itemId != null
                ? leaseReconciler.reconcileAndCount(itemId)
                : leaseReconciler.reconcileAndCount();
        int queuesRebuilt = queueReconciler.reconcileAndCount();

        // Every reconciled queue gets its ticket dispenser bumped to MAX(ticket).
        int itemsReconciled = itemId != null ? 1 : Math.max(leasesRebuilt, queuesRebuilt);
        return new ReconcileResponse(
                itemsReconciled,
                leasesRebuilt,
                queuesRebuilt,
                queuesRebuilt,
                startedAt,
                clock.instant());
    }
}
