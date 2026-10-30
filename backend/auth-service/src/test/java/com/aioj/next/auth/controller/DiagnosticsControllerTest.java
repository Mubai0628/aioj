package com.aioj.next.auth.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aioj.next.contract.diagnostics.ClientErrorReport;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Anyone can post a client error report; what it says must not forge log lines or carry what the user typed (T2.5). */
class DiagnosticsControllerTest {
    @Test
    void aReportCannotForgeLinesAndItsUrlLosesTheQuery() {
        Logger logger = (Logger) LoggerFactory.getLogger("client.diagnostics");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new DiagnosticsController().reportError(new ClientErrorReport("js",
                    "boom\r\n2026-10-30 ERROR forged line", null, 500, "bad trace\nid",
                    "https://oj.example/problems/7?keyword=CANARY-SEARCH#CANARY-FRAGMENT", "UA\tx", null));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).doesNotContain("\n", "\r", "\t", "CANARY-SEARCH", "CANARY-FRAGMENT")
                .contains("traceId=invalid", "url=https://oj.example/problems/7 ", "boom  2026-10-30 ERROR forged line");
    }
}
