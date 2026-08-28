package com.tf.reader.hold.repository;

import com.tf.reader.hold.entity.Hold;
import com.tf.reader.hold.entity.HoldStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

// Mongo repository for Hold documents.
//
// Thin on purpose — every method here exists for a named query somebody
// actually calls. Guarded, status-in-the-filter updates live in HoldWrites,
// not here, because a plain save() can't express "only if it's still QUEUED."
public interface HoldRepository extends MongoRepository<Hold, String> {

    Optional<Hold> findByHoldId(String holdId);

    Optional<Hold> findByScopeAndItemIdAndUserId(String scope, String itemId, String userId);

    List<Hold> findByUserId(String userId);

    // The front of the line, and the rebuild after a Redis wipe.
    List<Hold> findByScopeAndItemIdOrderByTicketAsc(String scope, String itemId);

    List<Hold> findByScopeAndItemIdAndStatusOrderByTicketAsc(String scope, String itemId, HoldStatus status);

    // Every institution currently queuing for this item — HoldPromotion's
    // published signature carries no scope, so this is how the fan-out
    // finds which institutions' queues to promote.
    List<Hold> findByItemIdAndStatus(String itemId, HoldStatus status);

    // Candidates for the sweep. The guard that actually decides whether one
    // is truly lapsed happens per-document in HoldWrites#expireIfLapsed.
    List<Hold> findByStatusAndOfferExpiresAtBefore(HoldStatus status, Instant instant);

    // Every OFFERED hold, any item — the reconciler's rebuild read (LiveOfferQuery).
    List<Hold> findByStatus(HoldStatus status);
}
