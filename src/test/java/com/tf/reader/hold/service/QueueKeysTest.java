package com.tf.reader.hold.service;

import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The one place every Redis key is built. QueueService, PromotionService and
// AvailabilityQueryImpl all call these — a typo here would make them silently
// disagree about which key they mean.
class QueueKeysTest {

    @Test
    void buildsTheKeysExactlyAsTheBuildDocSpecifies() {
        assertThat(QueueKeys.queueKey("inst_1", "item_1")).isEqualTo("queue:inst_1:item_1");
        assertThat(QueueKeys.ticketKey("inst_1", "item_1")).isEqualTo("queueseq:inst_1:item_1");
        assertThat(QueueKeys.promoteLockKey("inst_1", "item_1")).isEqualTo("promote:inst_1:item_1");
        assertThat(QueueKeys.member("user_a")).isEqualTo("u:user_a");
    }

    @Test
    void memberAndUserOfRoundTrip() {
        assertThat(QueueKeys.userOf(QueueKeys.member("user_a"))).isEqualTo("user_a");
    }

    @Test
    void requireScopeRefusesAMissingInstitution() {
        assertThatThrownBy(() -> QueueKeys.requireScope(null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> QueueKeys.requireScope("  "))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void requireScopePassesThroughARealScope() {
        assertThat(QueueKeys.requireScope("inst_1")).isEqualTo("inst_1");
    }
}
