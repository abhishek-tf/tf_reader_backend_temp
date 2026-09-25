package com.tf.reader.reading.controller;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.tf.reader.hold.service.QueueReconciler;
import com.tf.reader.reading.dto.ReconcileRequest;
import com.tf.reader.reading.dto.ReconcileResponse;
import com.tf.reader.reading.service.ReconcilerService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Everything here is a plain mock call-through, so no Spring context is needed — the
// wiring of this controller onto the admin filter chain is covered by SecurityConfig's
// own tests, not here.
class OpsControllerTest {

    private final ReconcilerService leaseReconciler = mock(ReconcilerService.class);
    private final QueueReconciler queueReconciler = mock(QueueReconciler.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-25T09:00:00Z"), ZoneOffset.UTC);

    private final OpsController controller = new OpsController(leaseReconciler, queueReconciler, clock);

    @Test
    void reconcilesEverythingWhenNoItemIdGiven() {
        when(leaseReconciler.reconcileAndCount()).thenReturn(3);
        when(queueReconciler.reconcileAndCount()).thenReturn(2);

        ReconcileResponse response = controller.reconcile(null);

        assertThat(response.leasesRebuilt()).isEqualTo(3);
        assertThat(response.queuesRebuilt()).isEqualTo(2);
        assertThat(response.itemsReconciled()).isEqualTo(3);
        verify(leaseReconciler, never()).reconcileAndCount("item_1");
    }

    @Test
    void reconcilesOneItemWhenItemIdGiven() {
        when(leaseReconciler.reconcileAndCount("item_1")).thenReturn(1);
        when(queueReconciler.reconcileAndCount()).thenReturn(5);

        ReconcileResponse response = controller.reconcile(new ReconcileRequest("item_1"));

        assertThat(response.itemsReconciled()).isEqualTo(1);
        assertThat(response.leasesRebuilt()).isEqualTo(1);
        verify(leaseReconciler, never()).reconcileAndCount();
    }
}
