package com.aioj.next.ai.model.resilience;

import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ProviderId;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plan A6.2: a scope's concurrency can change at run time and counts for permits taken afterwards. */
class ScopeBulkheadsTest {
    private final ScopeBulkheads bulkheads = new ScopeBulkheads(scope -> 2, 4, Duration.ofMillis(50));

    @Test
    void aLowerLimitWaitsForRunningCallsAndAHigherOneFreesPermitsAtOnce() {
        ScopeBulkheads.Permit first = take(2);
        ScopeBulkheads.Permit second = take(2);

        assertThatThrownBy(() -> take(1))
                .as("lowered to 1 while two calls hold permits: they keep them and a new call waits")
                .isInstanceOfSatisfying(ModelException.class,
                        error -> assertThat(error.kind()).isEqualTo(ModelErrorKind.UNAVAILABLE));
        first.close();
        assertThat(bulkheads.available("REPORT_ANALYSIS")).isZero();
        second.close();
        assertThat(bulkheads.available("REPORT_ANALYSIS")).isEqualTo(1);

        ScopeBulkheads.Permit third = take(3);
        assertThat(bulkheads.available("REPORT_ANALYSIS")).isEqualTo(2);
        third.close();
    }

    @Test
    void withoutASettingTheScopesDefaultApplies() {
        ScopeBulkheads.Permit raised = take(3);
        raised.close();

        ScopeBulkheads.Permit defaulted = bulkheads.acquire("REPORT_ANALYSIS", null, null, null,
                ProviderId.DEEPSEEK, "deepseek-v4-pro");
        assertThat(bulkheads.available("REPORT_ANALYSIS")).isEqualTo(1);
        defaulted.close();
    }

    private ScopeBulkheads.Permit take(int limit) {
        return bulkheads.acquire("REPORT_ANALYSIS", limit, null, null, ProviderId.DEEPSEEK, "deepseek-v4-pro");
    }
}
