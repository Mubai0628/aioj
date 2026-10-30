package com.aioj.next.common.web;

import com.aioj.next.common.api.TraceIds;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {
    /** Spring Security's filter chain is registered at -100; the internal token filters run inside it or later. */
    private static final int SECURITY_FILTER_ORDER = -100;

    @Test
    void runsAheadOfSpringSecurityAndTheTokenFilters() {
        assertThat(new TraceIdFilter().getOrder()).isLessThan(SECURITY_FILTER_ORDER);
    }

    @Test
    void aSafeIncomingIdIsUsedForTheRequestAndRemovedAfterwards() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        MockHttpServletResponse response = filter("abc-123", seen);

        assertThat(seen.get()).isEqualTo("abc-123");
        assertThat(response.getHeader(TraceIds.HEADER)).isEqualTo("abc-123");
        assertThat(MDC.get(TraceIds.MDC_KEY)).isNull();
    }

    @Test
    void anUnsafeIncomingIdIsReplacedBeforeItReachesTheLog() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        String forged = "abc\r\nX-Forged: 1";
        MockHttpServletResponse response = filter(forged, seen);

        assertThat(seen.get()).isNotEqualTo(forged);
        assertThat(TraceIds.isValid(seen.get())).isTrue();
        assertThat(response.getHeader(TraceIds.HEADER)).isEqualTo(seen.get());
    }

    private static MockHttpServletResponse filter(String incoming, AtomicReference<String> seen) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/problems");
        request.addHeader(TraceIds.HEADER, incoming);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new TraceIdFilter().doFilter(request, response, (req, res) -> seen.set(MDC.get(TraceIds.MDC_KEY)));
        return response;
    }
}
