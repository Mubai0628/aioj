package com.aioj.next.ai.agent.problem.tool;

import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedback;
import com.aioj.next.agent.harness.sandbox.api.ExecutionFeedbacks;
import com.aioj.next.agent.harness.tool.api.ExecutionClass;
import com.aioj.next.agent.harness.tool.api.FailurePolicy;
import com.aioj.next.agent.harness.tool.api.ToolAuditLevel;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.ai.agent.context.DataClassification;
import com.aioj.next.ai.agent.context.TrustLevel;
import com.aioj.next.ai.agent.policy.GuardDecisionRecorder;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.ai.agent.tool.SourceRef;
import com.aioj.next.ai.agent.tool.ToolExecutionContext;
import com.aioj.next.ai.agent.tool.ToolResult;
import com.aioj.next.ai.agent.tool.ToolStatus;
import com.aioj.next.ai.domain.ProblemServiceClient;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.contract.ai.AiSubmissionContextRequest;
import com.aioj.next.contract.ai.AiSubmissionContextResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The tutor's code tools (plan T1.11, A5.2): compile or run code for a practice question. The
 * model never passes code. It names where the code comes from, and the server reads it: one of
 * the caller's own submissions (ownership checked by problem-service, and only when its code is
 * allowed to the model), the code selected in this turn, or (plan 15.5) the code in the problem
 * page's editor, which the page sends with the turn. The turn binds the last two server-side.
 * Contest-restricted turns never see these tools (the contest tool filter, fail closed). The
 * program's output is untrusted data.
 */
abstract class TutorCodeTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(TutorCodeTool.class);
    static final String BINDING_SELECTION = "turn.selection";
    static final String BINDING_PROBLEM_ID = "turn.problemId";
    static final String BINDING_SUBMISSION_ID = "turn.submissionId";
    static final String BINDING_EDITOR_CODE = "turn.editorCode";
    static final String BINDING_EDITOR_LANGUAGE = "turn.editorLanguage";
    /** agent-executor's limit for source code. */
    static final int MAX_EDITOR_CODE_BYTES = 256 * 1024;
    static final Set<String> LANGUAGES = Set.of("cpp", "java", "python");
    static final int MAX_OUTPUT_CHARS = 2_000;

    protected final ProblemServiceClient problemServiceClient;
    protected final GuardDecisionRecorder guardDecisionRecorder;
    protected final TutorCodeExecutor executor;
    protected final ObjectMapper objectMapper;
    private final ToolDescriptor descriptor;

    TutorCodeTool(ProblemServiceClient problemServiceClient, GuardDecisionRecorder guardDecisionRecorder,
                  TutorCodeExecutor executor, ObjectMapper objectMapper, String name, String description) {
        this.problemServiceClient = problemServiceClient;
        this.guardDecisionRecorder = guardDecisionRecorder;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.descriptor = new ToolDescriptor(name, "1.0.0", description, schema(), Set.of("AI_CHAT"), 6000,
                Duration.ofSeconds(60), "sandbox", ExecutionClass.SANDBOX, FailurePolicy.FAIL_OPEN, ToolAuditLevel.FULL);
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    /** The tool's own input fields, besides where the code comes from. */
    protected void addInputs(ObjectNode properties, List<String> required) {
    }

    /** Where the code came from, read by the server; never from the model. */
    record CodeSource(String origin, String language, String code, Long problemId, Long submissionId) {
        SourceRef sourceRef(ToolExecutionContext context) {
            return submissionId != null ? new SourceRef("SUBMISSION", String.valueOf(submissionId))
                    : new SourceRef(origin, context.turnId());
        }

        Map<String, Object> describe() {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("origin", origin);
            source.put("language", language);
            if (submissionId != null) {
                source.put("submissionId", String.valueOf(submissionId));
            }
            return source;
        }
    }

    /** The resolved code, or the refusal to return instead. */
    record Resolution(CodeSource source, ToolResult<Object> refusal) {
    }

    protected Resolution resolve(ToolExecutionContext context, JsonNode input) {
        String origin = input.path("source").asString("");
        if ("editor".equals(origin)) {
            String code = context.serverBinding(BINDING_EDITOR_CODE);
            if (code == null || code.isBlank()) {
                return refuse(ToolStatus.NOT_FOUND, "NO_EDITOR_CODE", "the editor sent no code with this turn");
            }
            if (code.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_EDITOR_CODE_BYTES) {
                return refuse(ToolStatus.POLICY_DENIED, "EDITOR_CODE_TOO_LARGE",
                        "the editor's code is larger than the 256 KB the executor runs");
            }
            String language = normalizeLanguage(context.serverBinding(BINDING_EDITOR_LANGUAGE));
            if (language == null) {
                language = normalizeLanguage(input.path("language").asString(""));
            }
            if (language == null) {
                return refuse(ToolStatus.SCHEMA_ERROR, "LANGUAGE_REQUIRED",
                        "the editor's code needs its language: cpp, java or python");
            }
            return new Resolution(new CodeSource("EDITOR", language, code,
                    parseId(context.serverBinding(BINDING_PROBLEM_ID)), null), null);
        }
        if ("selection".equals(origin)) {
            String selected = context.serverBinding(BINDING_SELECTION);
            if (selected == null || selected.isBlank()) {
                return refuse(ToolStatus.NOT_FOUND, "NO_SELECTED_CODE", "no code is selected in this turn");
            }
            String language = normalizeLanguage(input.path("language").asString(""));
            if (language == null) {
                return refuse(ToolStatus.SCHEMA_ERROR, "LANGUAGE_REQUIRED",
                        "selected code needs its language: cpp, java or python");
            }
            return new Resolution(new CodeSource("SELECTION", language, selected,
                    parseId(context.serverBinding(BINDING_PROBLEM_ID)), null), null);
        }
        if (!"submission".equals(origin)) {
            return refuse(ToolStatus.SCHEMA_ERROR, "SOURCE_REQUIRED", "source must be submission, selection or editor");
        }
        // Both branches boxed: a primitive one would unbox a missing binding into a NullPointerException.
        Long submissionId = input.path("submissionId").isIntegralNumber()
                ? Long.valueOf(input.path("submissionId").asLong())
                : parseId(context.serverBinding(BINDING_SUBMISSION_ID));
        if (submissionId == null) {
            return refuse(ToolStatus.SCHEMA_ERROR, "SUBMISSION_ID_REQUIRED",
                    "submissionId is required when the conversation is not about a submission");
        }
        AiSubmissionContextResponse submission;
        try {
            submission = problemServiceClient.aiSubmissionContext(new AiSubmissionContextRequest(
                    context.userId(), submissionId, null, null, null, null, "AI_CHAT"));
        } catch (DomainException ex) {
            if (ex.errorCode() == ErrorCode.FORBIDDEN) {
                return new Resolution(null, ProblemToolSupport.deny(guardDecisionRecorder, objectMapper, context,
                        descriptor.name(), ProblemToolSupport.REASON_FOREIGN_SUBMISSION_VIEW_DENIED,
                        objectMapper.createObjectNode().put("submissionId", submissionId), false));
            }
            if (ex.errorCode() == ErrorCode.NOT_FOUND) {
                return new Resolution(null, ProblemToolSupport.unifiedDenial());
            }
            return refuse(ToolStatus.EXECUTION_ERROR, "SUBMISSION_CONTEXT_FAILED", "submission context lookup failed");
        }
        if (submission == null) {
            return new Resolution(null, ProblemToolSupport.unifiedDenial());
        }
        // Contest-active code is withheld from the model; it is not run for it either.
        if (!submission.codeAllowedToModel() || submission.codeText() == null || submission.codeText().isBlank()) {
            return refuse(ToolStatus.POLICY_DENIED, "CODE_NOT_ALLOWED",
                    "this submission's code is not available to the assistant");
        }
        String language = normalizeLanguage(submission.language());
        if (language == null) {
            return refuse(ToolStatus.EXECUTION_ERROR, "LANGUAGE_NOT_SUPPORTED",
                    "the code tools run cpp, java and python only");
        }
        return new Resolution(new CodeSource("SUBMISSION", language, submission.codeText(), submission.problemId(),
                submissionId), null);
    }

    /** UNAVAILABLE and REJECTED end the call; the turn goes on without the run. */
    protected ToolResult<Object> failureOf(ExecutionFeedback feedback, String executionId) {
        if (feedback.status() == ExecutionFeedback.Status.UNAVAILABLE) {
            return ToolResult.failure(null, ToolStatus.SERVICE_UNAVAILABLE, "EXECUTOR_UNAVAILABLE",
                    "UNAVAILABLE: the code executor cannot be reached right now (executionId " + executionId
                            + "); answer without running the code");
        }
        return ToolResult.failure(null, ToolStatus.EXECUTION_ERROR, "EXECUTION_REJECTED",
                "the code executor refused this run (executionId " + executionId + ")");
    }

    protected ToolResult<Object> success(ToolExecutionContext context, CodeSource source, Map<String, Object> data) {
        return ToolResult.success(null, data, List.of(source.sourceRef(context)), DataClassification.USER_PRIVATE,
                TrustLevel.EXTERNAL_UNTRUSTED);
    }

    /** The A5.1 fields of one execution, as the model sees them. */
    protected Map<String, Object> feedbackView(ExecutionFeedback feedback) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("status", feedback.status().name());
        view.put("verdict", feedback.verdict() == null ? null : feedback.verdict().name());
        view.put("timeMillis", feedback.cpuMillis());
        view.put("memoryKb", feedback.memoryBytes() / 1024L);
        view.put("processPeak", feedback.processPeak());
        if (!feedback.diagnostics().isEmpty()) {
            view.put("diagnostics", diagnosticsView(feedback.diagnostics()));
        }
        if (!feedback.stderrSummary().isBlank()) {
            view.put("stderrSummary", feedback.stderrSummary());
        }
        return view;
    }

    protected List<Map<String, Object>> diagnosticsView(List<ExecutionFeedback.CompileDiagnostic> diagnostics) {
        List<Map<String, Object>> view = new ArrayList<>();
        for (ExecutionFeedback.CompileDiagnostic diagnostic : diagnostics) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("line", diagnostic.line());
            node.put("column", diagnostic.column());
            node.put("message", diagnostic.message());
            view.add(node);
        }
        return view;
    }

    protected Map<String, Object> differenceView(ExecutionFeedbacks.FirstDifference difference) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("line", difference.line());
        view.put("column", difference.column());
        view.put("expected", difference.expected());
        view.put("actual", difference.actual());
        return view;
    }

    /** One line per execution, so an executionId the model cites can be looked up (no code, no output). */
    protected void logExecution(ToolExecutionContext context, String executionId, String outcome) {
        log.info("code tool execution executionId={} tool={} userId={} turnId={} outcome={}", executionId,
                descriptor.name(), context.userId(), context.turnId(), outcome);
    }

    protected static String executionId() {
        return "exec-" + UUID.randomUUID();
    }

    protected static String cacheScope(ToolExecutionContext context) {
        return "user:" + context.userId();
    }

    protected static String cap(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > MAX_OUTPUT_CHARS ? value.substring(0, MAX_OUTPUT_CHARS) : value;
    }

    private static Resolution refuse(ToolStatus status, String code, String message) {
        return new Resolution(null, ToolResult.failure(null, status, code, message));
    }

    private static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String value = language.strip().toLowerCase(Locale.ROOT);
        String normalized = switch (value) {
            case "c++", "cpp17", "c++17", "cpp" -> "cpp";
            case "py", "python3", "python" -> "python";
            default -> value;
        };
        return LANGUAGES.contains(normalized) ? normalized : null;
    }

    private static Long parseId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private JsonNode schema() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        ObjectNode source = properties.putObject("source");
        source.put("type", "string");
        ArrayNode values = source.putArray("enum");
        values.add("submission");
        values.add("selection");
        values.add("editor");
        source.put("description", "Where the code comes from: one of the user's own submissions, the code the user "
                + "selected in this turn, or the code in the problem page's editor (sent with this turn). You cannot "
                + "pass code yourself.");
        ObjectNode submissionId = properties.putObject("submissionId");
        submissionId.put("type", "integer");
        submissionId.put("description", "With source=submission: the submission id. Defaults to the submission this "
                + "conversation is about.");
        ObjectNode language = properties.putObject("language");
        language.put("type", "string");
        ArrayNode languages = language.putArray("enum");
        languages.add("cpp");
        languages.add("java");
        languages.add("python");
        language.put("description", "With source=selection: the language of the selected code. With "
                + "source=editor it is known already.");
        List<String> required = new ArrayList<>(List.of("source"));
        addInputs(properties, required);
        ArrayNode requiredNode = root.putArray("required");
        required.forEach(requiredNode::add);
        root.put("additionalProperties", false);
        return root;
    }
}
