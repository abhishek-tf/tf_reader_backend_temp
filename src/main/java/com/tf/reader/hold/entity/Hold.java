package com.tf.reader.hold.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

// Mongo document for a reader's place in the queue.
//
// ONE live hold per reader per title per institution. Two taps in the same
// millisecond both pass any check written in Java; only the unique index
// below stops the second one — that index IS the guard, not an optimisation.
@Document("holds")
@CompoundIndexes({
        @CompoundIndex(name = "one_live_hold",
                def = "{'scope': 1, 'itemId': 1, 'userId': 1}", unique = true),
        @CompoundIndex(name = "queue_order",
                def = "{'scope': 1, 'itemId': 1, 'ticket': 1}"),
        @CompoundIndex(name = "my_holds",
                def = "{'userId': 1}"),
        @CompoundIndex(name = "lapsing",
                def = "{'status': 1, 'offer.expiresAt': 1}")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Hold {

    @Id
    private String id;             // Mongo's own. Never appears in a URL.

    @Indexed(unique = true)
    private String holdId;         // public, prefixed: "hold_5d1..."
    private String userId;         // from the verified token, never a body
    private String scope;          // institutionId — never null, see QueueKeys
    private String itemId;         // opaque, never parsed
    private HoldStatus status;
    private long ticket;           // monotonic — the ONLY reason the line can be rebuilt
    private Instant placedAt;
    private Offer offer;           // null unless status is OFFERED

    // Deliberately absent: a `position` field. The contract says it is
    // "derived from a monotonic ticket, never stored" — a stored position is
    // wrong the instant anybody ahead cancels.

    public static Hold queued(String userId, String scope, String itemId, long ticket, Instant now) {
        Hold h = new Hold();
        h.id = null;
        h.holdId = "hold_" + UUID.randomUUID().toString().substring(0, 8);
        h.userId = userId;
        h.scope = scope;
        h.itemId = itemId;
        h.status = HoldStatus.QUEUED;
        h.ticket = ticket;
        h.placedAt = now;
        return h;
    }
}
