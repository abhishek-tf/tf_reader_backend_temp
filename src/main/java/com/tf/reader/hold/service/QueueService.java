package com.tf.reader.hold.service;

import com.tf.reader.catalogue.api.EntitlementDecision;
import com.tf.reader.catalogue.api.EntitlementQuery;
import com.tf.reader.catalogue.api.SubjectRef;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.hold.api.HoldView;
import com.tf.reader.hold.api.OfferView;
import com.tf.reader.hold.entity.Hold;
import com.tf.reader.hold.entity.HoldStatus;
import com.tf.reader.hold.repository.HoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

// Maintains the ordered wait queue for a title. join and holdsFor today —
// leave and accept land once promotion and the copy lease exist. Position
// and queueLength are always computed here, on read, from Redis — never
// stored.
@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

    private final HoldRepository holds;
    private final StringRedisTemplate redis;
    private final EntitlementQuery entitlements;
    private final Clock clock;

    public QueueService(HoldRepository holds, StringRedisTemplate redis,
                         EntitlementQuery entitlements, Clock clock) {
        this.holds = holds;
        this.redis = redis;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    public Placed join(CurrentUser me, String itemId) {
        String scope = QueueKeys.requireScope(me.institutionId());

        EntitlementDecision decision = entitlements.check(new SubjectRef(me.userId(), scope), itemId);
        if (!decision.entitled()) {
            // The deny reason, unchanged — "your subscription lapsed" and
            // "your library never had this" are different sentences.
            throw new ApiException(mapDenyReason(decision), decision.reason() == null
                    ? "Not entitled" : decision.reason().name());
        }
        if (decision.copies() == null) {
            // Decide from the copy count, not a tier name — the licence
            // list has already changed once. Unlimited means there's
            // nothing to wait for.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "This title has no copy limit — nothing to queue for");
        }

        Optional<Hold> existing = holds.findByScopeAndItemIdAndUserId(scope, itemId, me.userId());
        if (existing.isPresent()) {
            // Already queued: 200 with the SAME position. Re-joining must
            // never move somebody to the back of a line they were already in.
            return new Placed(viewOf(existing.get(), decision), false);
        }

        long ticket = requireNonNull(redis.opsForValue().increment(QueueKeys.ticketKey(scope, itemId)));
        Hold hold = Hold.queued(me.userId(), scope, itemId, ticket, clock.instant());

        Hold saved;
        try {
            saved = holds.save(hold);
        } catch (DuplicateKeyException e) {
            // The index throwing on a double tap is the design working —
            // a clean 200 with the winner's row, never a 500.
            saved = holds.findByScopeAndItemIdAndUserId(scope, itemId, me.userId()).orElseThrow(() -> e);
            return new Placed(viewOf(saved, decision), false);
        }

        redis.opsForZSet().add(QueueKeys.queueKey(scope, itemId), QueueKeys.member(me.userId()), ticket);
        log.info("HOLD_PLACED user={} item={}", me.userId(), itemId); // becomes ChangeLog.record() once the port exists
        return new Placed(viewOf(saved, decision), true);
    }

    public List<HoldView> holdsFor(String userId) {
        return holds.findByUserId(userId).stream().map(h -> viewOf(h, null)).toList();
    }

    private HoldView viewOf(Hold h, EntitlementDecision known) {
        String queueKey = QueueKeys.queueKey(h.getScope(), h.getItemId());
        int queueLength = sizeOf(queueKey);

        if (h.getStatus() == HoldStatus.OFFERED) {
            OfferView offerView = new OfferView(h.getOffer().getOfferId(), h.getOffer().getOfferedAt(), h.getOffer().getExpiresAt());
            // There's a real deadline instead — mixing a guess with a fact
            // on one card is confusing, so estimatedWaitDays is omitted.
            return new HoldView(h.getHoldId(), h.getItemId(), h.getStatus().name(), 0, queueLength, null, h.getPlacedAt(), offerView);
        }

        // A missing rank means Redis and Mongo disagree about a hold that
        // exists right now — defaulting to position 1 would show a false
        // "you're first" to whoever actually queried this. That's the
        // reconciler's problem to fix, not something to paper over here.
        Long rank = redis.opsForZSet().rank(queueKey, QueueKeys.member(h.getUserId()));
        if (rank == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "Queue position unavailable for hold " + h.getHoldId());
        }
        int position = rank.intValue() + 1;
        Integer estimatedWaitDays = estimateWaitDays(h, position, known);
        return new HoldView(h.getHoldId(), h.getItemId(), h.getStatus().name(), position, queueLength, estimatedWaitDays, h.getPlacedAt(), null);
    }

    private Integer estimateWaitDays(Hold h, int position, EntitlementDecision known) {
        try {
            EntitlementDecision decision = known != null ? known
                    : entitlements.check(new SubjectRef(h.getUserId(), h.getScope()), h.getItemId());
            if (decision.copies() == null || decision.copies() <= 0) {
                return null;
            }
            int aheadOfMe = position - 1;
            int cyclesAhead = aheadOfMe / decision.copies();
            return (cyclesAhead + 1) * decision.loanPeriodDays(); // a guess, never a promise
        } catch (RuntimeException e) {
            return null; // knows nothing about early returns — never let this block a response
        }
    }

    private int sizeOf(String key) {
        Long size = redis.opsForZSet().zCard(key);
        return size == null ? 0 : size.intValue();
    }

    private static ErrorCode mapDenyReason(EntitlementDecision decision) {
        if (decision.reason() == null) {
            return ErrorCode.NO_ENTITLEMENT;
        }
        return switch (decision.reason()) {
            case NO_ENTITLEMENT -> ErrorCode.NO_ENTITLEMENT;
            case ENTITLEMENT_EXPIRED -> ErrorCode.ENTITLEMENT_EXPIRED;
            case ENTITLEMENT_SUSPENDED -> ErrorCode.ENTITLEMENT_SUSPENDED;
            case INSTITUTION_INACTIVE -> ErrorCode.INSTITUTION_INACTIVE;
            case CONTENT_NOT_READY -> ErrorCode.CONTENT_NOT_READY;
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
        };
    }

    private static long requireNonNull(Long value) {
        // A null here means Redis didn't return a ticket at all — treating
        // that as ticket 1 would hand out a duplicate and corrupt queue
        // order. Fail loudly instead of guessing.
        if (value == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Redis did not return a ticket");
        }
        return value;
    }

    public record Placed(HoldView view, boolean created) {
    }
}
