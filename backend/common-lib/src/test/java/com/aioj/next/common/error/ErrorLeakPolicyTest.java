package com.aioj.next.common.error;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan S7 (T2.6): what leaves for a client (an HTTP body, a stream event, a stored error a page
 * shows) or for the model (a tool's failure, an execution's feedback) never carries an exception's
 * text or a response body. Clients get error keys and the keys' text; the failure itself goes to
 * the log through {@link SafeErrors}. This scan looks at one call at a time; the marker tests in
 * each module cover what travels through a variable first.
 */
class ErrorLeakPolicyTest {
    private static final Path BACKEND = Path.of("..");
    private static final Pattern SINK = Pattern.compile("(?:\\bwriteSseEvent|\\bToolResult\\.failure|\\bnew ExecutionFeedback"
            + "|\\.set(?:ErrorMessage|FailureSummary|LastErrorMessage|JudgeMessage|ErrorSummary|LastErrorSummary)"
            + "|\\bmarkFailed|\\bmarkSystemError|\\bfailJob|\\brecordFailure|\\bfailMessage|\\bneedsAttention"
            + "|\\bJudgeResult\\.systemError|\\bnew JudgeResult|\\bnew CaseRunOutcome|\\berrors\\.add"
            + "|\\bnew [A-Z][A-Za-z]*(?:Response|ResultItem)"
            + "|\\.set\\(\"(?:error_message|last_error_message|failure_summary|error_summary)\")\\(");
    private static final Pattern EXCEPTION_TEXT = Pattern.compile(
            "\\b(?:ex|e|exception|error|cause|failure|thrown|throwable|t|domain|domainException|runtimeException)"
                    + "\\.(?:getMessage|getLocalizedMessage)\\(\\)|\\.getResponseBodyAsString\\(\\)");
    /**
     * Text that is the user's (or setter's) own and safe to show, by file and by what the call says.
     * Each entry is a decision, not a convenience: keep the reason next to it.
     */
    private static final Map<String, List<String>> ALLOWED = Map.of(
            // The format validator's words about the package the admin uploaded (entry names, missing files).
            "ProblemDraftTestcaseUploadService.java", List.of("\"TESTCASE_PACKAGE_FORMAT_INVALID\", ex.getMessage()"),
            // auth-service writes these batch-row reasons in Chinese for this very page; no lower-level text.
            "UserAccountService.java", List.of("\"FAILED\", ex.getMessage()", "ex.getMessage())"),
            "LearningGroupService.java", List.of("LearningGroupMemberBatchAddStatus.FAILED, error.getMessage()"),
            // "Unsupported language: <what the student picked>".
            "SandboxClient.java", List.of("SubmissionStatus.COMPILE_ERROR, ex.getMessage()"));

    @Test
    void nothingClientOrModelFacingCarriesAnExceptionsText() throws IOException {
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

        assertThat(violations).as("client- or model-facing calls that carry an exception's text; store an error key")
                .isEmpty();
    }

    @Test
    void theCheckItselfCatchesEachKindOfLeak() {
        List<String> violations = new ArrayList<>();
        check(Path.of("Sample.java"), """
                class Sample {
                    void run() {
                        writeSseEvent(output, "error", ex.getMessage());
                        job.setErrorMessage(summarize(ex.getMessage(), 480));
                        return ToolResult.failure(ToolStatus.EXECUTION_ERROR, "X", domain.getMessage());
                        return new ExecutionFeedback(Status.REJECTED, "", refused.getResponseBodyAsString(), "", 0L, 0L, false);
                        return new AiModelListResponse(scope, "FAILED", ex.getMessage(), List.of());
                        job.setErrorMessage(UserErrorFeedback.storedKey(ex));
                        writeSseEvent(output, "error", objectMapper.writeValueAsString(payload));
                        results.add(new Row(result.getMessage()));
                    }
                }
                """, violations);

        assertThat(violations).hasSize(5);
    }

    private static void check(Path source, String text, List<String> violations) {
        Matcher sink = SINK.matcher(text);
        List<String> allowed = ALLOWED.getOrDefault(source.getFileName().toString(), List.of());
        while (sink.find()) {
            String arguments = arguments(text, sink.end() - 1);
            if (arguments == null) {
                continue;
            }
            String flat = arguments.replaceAll("\\s+", " ").trim();
            String values = flat.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
            if (EXCEPTION_TEXT.matcher(values).find() && allowed.stream().noneMatch(flat::contains)) {
                int line = text.substring(0, sink.start()).split("\n", -1).length;
                violations.add(source.normalize() + ":" + line + " " + sink.group() + flat);
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
