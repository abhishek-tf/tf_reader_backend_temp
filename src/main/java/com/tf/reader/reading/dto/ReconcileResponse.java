package com.tf.reader.reading.dto;

import java.time.Instant;

public record ReconcileResponse(
        int itemsReconciled,
        int leasesRebuilt,
        int queuesRebuilt,
        int ticketDispensersReset,
        Instant startedAt,
        Instant finishedAt) {
}
