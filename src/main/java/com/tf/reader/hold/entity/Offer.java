package com.tf.reader.hold.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

// Embedded inside Hold, not its own Mongo document.
//
// Deliberately not a @Document with its own OfferRepository: accept (a
// reader claiming their turn) and the sweep (a turn lapsing) race each
// other, and the only way to make that race provable rather than hopeful is
// for both to be a single guarded update on ONE document. Splitting this
// into a second collection would turn that into a two-document operation
// with no way to make it atomic.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Offer {

    private String offerId;
    private Instant offeredAt;
    private Instant expiresAt;   // ABSOLUTE instant, never a duration
    private String leaseToken;   // CopyLease's opaque handle token — needed to extend/reassign/release later
}
