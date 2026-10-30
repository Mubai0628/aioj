package com.aioj.next.common.api;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** A caller-supplied trace id ends up in log lines and response headers (T2.5). */
class TraceIdsTest {
    @Test
    void keepsTheIdsOurProxiesAndServicesMint() {
        for (String minted : new String[] {
                "3f2a9c0e-1b2d-4e5f-8a9b-0c1d2e3f4a5b", // UUID: gateway, services
                "0123456789abcdef0123456789abcdef", // nginx $request_id
                "job.42_retry-1",
                "x".repeat(64)}) {
            assertThat(TraceIds.ensure(minted)).isEqualTo(minted);
        }
    }

    @Test
    void replacesAnythingThatCouldSplitALogLineOrAHeader() {
        for (String unsafe : Arrays.asList(null, "", " ", "a b", "line\nbreak", "cr\rlf", "tab\tx", "x".repeat(65),
                "{\"json\":1}", "名字", "a/b", "n/a")) {
            String id = TraceIds.ensure(unsafe);
            assertThat(id).isNotEqualTo(unsafe);
            assertThat(TraceIds.isValid(id)).isTrue();
        }
    }
}
