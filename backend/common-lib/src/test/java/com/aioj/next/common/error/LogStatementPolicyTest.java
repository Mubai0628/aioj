package com.aioj.next.common.error;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5 log redaction by construction (T2.5): no log call in any backend module hands the log an
 * exception's message, its toString or its stack trace, or a value that holds a prompt, code,
 * test data, reasoning or a secret. Exceptions go through {@link SafeErrors}; content is logged
 * as ids, sizes, kinds and status codes.
 */
class LogStatementPolicyTest {
    private static final Path BACKEND = Path.of("..");
    private static final Pattern CALL = Pattern.compile("\\b(?:log|logger)\\.(?:trace|debug|info|warn|error)\\(");
    private static final Pattern EXCEPTION_TEXT = Pattern.compile(
            "\\b(?:ex|e|exception|error|cause|failure|thrown|throwable|t)\\.(?:getMessage|getLocalizedMessage|toString)\\(\\)");
    private static final Pattern TRAILING_EXCEPTION = Pattern.compile(
            ",\\s*(?:ex|e|exception|cause|failure|thrown|throwable)\\s*\\)$");
    private static final Pattern CONTENT = Pattern.compile(
            "\\b(?:prompt|sourceCode|stdin|stdout|stderr|reasoning|reasoningContent|apiKey|password|secret"
                    + "|accessToken|refreshToken|rawBody|responseBody)\\b(?:\\(\\))?");

    @Test
    void noLogCallCarriesAnExceptionsTextOrContent() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> modules = Files.list(BACKEND)) {
            for (Path module : modules.filter(path -> Files.isDirectory(path.resolve("src/main/java"))).toList()) {
                try (Stream<Path> sources = Files.walk(module.resolve("src/main/java"))) {
                    for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                        check(source, Files.readString(source), violations);
                    }
                }
            }
        }

        assertThat(violations).as("log calls that can leak content; use SafeErrors or log an id/size/kind")
                .isEmpty();
    }

    @Test
    void theCheckItselfCatchesEachKindOfLeak() {
        List<String> violations = new ArrayList<>();
        check(Path.of("Sample.java"), """
                class Sample {
                    void run() {
                        log.warn("failed: {}", ex.getMessage());
                        log.error("failed for {}", id, ex);
                        log.info("prompt was {}", request.prompt());
                        log.warn("ok {} {}", id, SafeErrors.describe(ex));
                        log.info("type {}", ex.getClass().getSimpleName());
                    }
                }
                """, violations);

        assertThat(violations).hasSize(3);
    }

    private static void check(Path source, String text, List<String> violations) {
        Matcher call = CALL.matcher(text);
        while (call.find()) {
            String arguments = arguments(text, call.end() - 1);
            if (arguments == null) {
                continue;
            }
            // The format string's words are not values: "prompt was {}" is fine, request.prompt() is not.
            String values = arguments.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
            String flat = values.replaceAll("\\s+", " ").trim();
            if (EXCEPTION_TEXT.matcher(flat).find() || TRAILING_EXCEPTION.matcher(flat).find()
                    || CONTENT.matcher(flat).find()) {
                int line = text.substring(0, call.start()).split("\n", -1).length;
                violations.add(source.normalize() + ":" + line + " " + flat);
            }
        }
    }

    /** The text between the call's parentheses, the closing one included; null when unbalanced. */
    private static String arguments(String text, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return text.substring(open + 1, i + 1);
            }
        }
        return null;
    }
}
