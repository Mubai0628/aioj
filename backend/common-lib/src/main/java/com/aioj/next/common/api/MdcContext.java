package com.aioj.next.common.api;

import org.slf4j.MDC;

import java.util.Map;

/** Work handed to another thread logs, and calls other services, under the submitting thread's trace id. */
public final class MdcContext {
    private MdcContext() {
    }

    /** Captures the caller's MDC now; the worker thread gets it for the task and its own back afterwards. */
    public static Runnable wrap(Runnable task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            replace(context);
            try {
                task.run();
            } finally {
                replace(previous);
            }
        };
    }

    private static void replace(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
