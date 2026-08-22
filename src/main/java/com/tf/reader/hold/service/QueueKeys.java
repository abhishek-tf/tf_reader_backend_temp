package com.tf.reader.hold.service;

import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;

// Every Redis key hold touches, built in one place. QueueService,
// PromotionService and AvailabilityQueryImpl all need the identical string
// for the same title's queue — building it three separate times is exactly
// how one of them drifts by a typo and starts reading a different key.
public final class QueueKeys {

    private QueueKeys() {
    }

    public static String requireScope(String scope) {
        if (scope == null || scope.isBlank()) {
            // Individual/B2C tokens carry no institutionId, and B2C isn't
            // built yet. This fires instead of silently building
            // "queue:null:itemId".
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "No institution scope on this token");
        }
        return scope;
    }

    public static String queueKey(String scope, String itemId) {
        return "queue:" + scope + ":" + itemId;
    }

    public static String ticketKey(String scope, String itemId) {
        return "queueseq:" + scope + ":" + itemId;
    }

    public static String promoteLockKey(String scope, String itemId) {
        return "promote:" + scope + ":" + itemId;
    }

    public static String member(String userId) {
        return "u:" + userId;
    }

    public static String userOf(String member) {
        // Parses data this class itself wrote into Redis. A member that
        // doesn't start with the "u:" prefix means something else wrote to
        // this key, or the data is corrupt — fail loudly rather than throw
        // an unhelpful StringIndexOutOfBoundsException three lines away.
        if (member == null || !member.startsWith("u:")) {
            throw new IllegalStateException("Not a queue member: " + member);
        }
        return member.substring(2);
    }
}
