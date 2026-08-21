package com.tf.reader.hold.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tf.reader.hold.api.HoldView;
import com.tf.reader.hold.api.OfferView;

import java.time.Instant;

// Response body describing a hold. Same fields as a HoldView plus
// serverTime — every response in this contract carries the server's clock,
// so the app renders countdowns against it rather than its own.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HoldResponse(
        String holdId, String itemId, String status,
        int position, int queueLength, Integer estimatedWaitDays,
        Instant placedAt, OfferView offer, Instant serverTime) {

    public static HoldResponse of(HoldView v, Instant now) {
        return new HoldResponse(v.holdId(), v.itemId(), v.status(), v.position(),
                v.queueLength(), v.estimatedWaitDays(), v.placedAt(), v.offer(), now);
    }
}
