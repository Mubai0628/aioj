package com.aioj.next.ai.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/**
 * Every log line at INFO and above while it is open, rendered the way a log file would show it
 * (message, stack trace, MDC), for the log redaction tests (T2.5). Open it after any Spring
 * context has started: Boot's logging setup drops appenders added before it.
 */
public final class LogCanary implements AutoCloseable {
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    public LogCanary() {
        appender.start();
        root.addAppender(appender);
    }

    /** What INFO and above wrote. */
    public String text() {
        StringBuilder out = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            if (!event.getLevel().isGreaterOrEqual(Level.INFO)) {
                continue;
            }
            out.append(event.getLevel()).append(' ').append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                out.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
            }
            event.getMDCPropertyMap().forEach((key, value) -> out.append(key).append('=').append(value).append('\n'));
        }
        return out.toString();
    }

    /** How many lines INFO and above wrote, so a test can show its path did log. */
    public long lines() {
        return appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO)).count();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
    }
}
