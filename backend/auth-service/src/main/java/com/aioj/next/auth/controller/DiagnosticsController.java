package com.aioj.next.auth.controller;

import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.api.TraceIds;
import com.aioj.next.contract.diagnostics.ClientErrorReport;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/diagnostics")
public class DiagnosticsController {
    private static final Logger log = LoggerFactory.getLogger("client.diagnostics");

    /**
     * Anyone can post here, so nothing reaches the log as sent (T2.5): control characters become
     * spaces (no forged lines), the URL loses its query and fragment (they can carry what the user
     * typed), and a trace id that is not one of ours is not repeated.
     */
    @PostMapping("/error")
    public ApiResponse<Boolean> reportError(@RequestBody @Valid ClientErrorReport report) {
        log.warn("clientError kind={} code={} traceId={} url={} ua=\"{}\" msg=\"{}\"",
                clean(report.kind(), 32), report.code(),
                TraceIds.isValid(report.traceId()) ? report.traceId() : "invalid",
                pathOnly(report.url()), clean(report.userAgent(), 120), clean(report.message(), 200));
        if (report.stack() != null && !report.stack().isBlank()) {
            log.debug("clientErrorStack traceId={} stack=\n{}",
                    TraceIds.isValid(report.traceId()) ? report.traceId() : "invalid", report.stack());
        }
        return ApiResponse.ok(Boolean.TRUE);
    }

    static String clean(String s, int max) {
        if (s == null) return "";
        String flat = s.replaceAll("\\p{Cntrl}", " ");
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }

    static String pathOnly(String url) {
        if (url == null) return "";
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        int cut = query < 0 ? fragment : fragment < 0 ? query : Math.min(query, fragment);
        return clean(cut < 0 ? url : url.substring(0, cut), 200);
    }
}
