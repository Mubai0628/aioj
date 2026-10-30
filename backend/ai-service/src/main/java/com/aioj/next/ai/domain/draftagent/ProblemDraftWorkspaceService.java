package com.aioj.next.ai.domain.draftagent;

import com.aioj.next.ai.domain.AiConversationService;
import com.aioj.next.ai.domain.ProblemDraftStore;
import com.aioj.next.ai.domain.problem.ProblemDraftStaticValidator;
import com.aioj.next.ai.domain.problem.VerificationOptions;
import com.aioj.next.ai.domain.problem.VerificationReport;
import com.aioj.next.ai.persistence.entity.AiMessageEntity;
import com.aioj.next.ai.persistence.entity.AiAgentRunEntity;
import com.aioj.next.ai.persistence.entity.AiToolCallEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftTestcaseArtifactEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftWorkspaceEntity;
import com.aioj.next.ai.persistence.mapper.AiMessageMapper;
import com.aioj.next.ai.persistence.mapper.AiAgentRunMapper;
import com.aioj.next.ai.persistence.mapper.AiToolCallMapper;
import com.aioj.next.ai.persistence.mapper.ProblemDraftMapper;
import com.aioj.next.ai.persistence.mapper.ProblemDraftTestcaseArtifactMapper;
import com.aioj.next.ai.persistence.mapper.ProblemDraftWorkspaceMapper;
import com.aioj.next.common.api.JsonLongIds;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.error.UserErrorFeedback;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.ai.AiConversationCreateRequest;
import com.aioj.next.contract.ai.AiConversationResponse;
import com.aioj.next.contract.ai.ProblemDraftImportReadiness;
import com.aioj.next.contract.ai.ProblemDraftRefineRequest;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.aioj.next.contract.ai.ProblemDraftReviewRisk;
import com.aioj.next.contract.ai.ProblemDraftTestcaseArtifactSummary;
import com.aioj.next.contract.ai.ProblemDraftWorkspaceMessageResponse;
import com.aioj.next.contract.ai.ProblemDraftWorkspaceResponse;
import com.aioj.next.contract.ai.ProblemDraftAgentRunResponse;
import com.aioj.next.contract.ai.ProblemDraftToolCallResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ProblemDraftWorkspaceService {
    public static final String CONVERSATION_SOURCE = "problem_draft_agent";
    public static final String SCOPE = "AI_PROBLEM_DRAFT";
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_NEEDS_ATTENTION = "NEEDS_ATTENTION";
    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final Set<String> PATCHABLE_FIELDS = Set.of(
            "title", "difficulty", "statement", "notes", "standardSolutionLanguage",
            "standardSolutionCode", "referenceSolutionLanguage", "referenceSolutionCode",
            "testcaseGeneratorPython", "stressTestcaseGeneratorPython", "generationPlan",
            "tags", "testCases", "timeLimitMillis", "memoryLimitKb"
    );

    private final ProblemDraftWorkspaceMapper workspaceMapper;
    private final ProblemDraftMapper draftMapper;
    private final ProblemDraftTestcaseArtifactMapper artifactMapper;
    private final AiMessageMapper messageMapper;
    private final AiAgentRunMapper agentRunMapper;
    private final AiToolCallMapper toolCallMapper;
    private final AiConversationService conversationService;
    private final ProblemDraftStore draftStore;
    private final ProblemDraftStaticValidator staticValidator;
    private final ProblemDraftFingerprintService fingerprintService;
    private final ProblemDraftReviewRiskService riskService;
    private final ObjectMapper objectMapper;

    public ProblemDraftWorkspaceService(
            ProblemDraftWorkspaceMapper workspaceMapper,
            ProblemDraftMapper draftMapper,
            ProblemDraftTestcaseArtifactMapper artifactMapper,
            AiMessageMapper messageMapper,
            AiAgentRunMapper agentRunMapper,
            AiToolCallMapper toolCallMapper,
            AiConversationService conversationService,
            ProblemDraftStore draftStore,
            ProblemDraftStaticValidator staticValidator,
            ProblemDraftFingerprintService fingerprintService,
            ProblemDraftReviewRiskService riskService,
            ObjectMapper objectMapper
    ) {
        this.workspaceMapper = workspaceMapper;
        this.draftMapper = draftMapper;
        this.artifactMapper = artifactMapper;
        this.messageMapper = messageMapper;
        this.agentRunMapper = agentRunMapper;
        this.toolCallMapper = toolCallMapper;
        this.conversationService = conversationService;
        this.draftStore = draftStore;
        this.staticValidator = staticValidator;
        this.fingerprintService = fingerprintService;
        this.riskService = riskService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProblemDraftWorkspaceEntity create(Long userId, ProblemDraftRequest request) {
        if (userId == null || request == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Problem draft workspace request is required");
        }
        String workspaceId = UUID.randomUUID().toString().replace("-", "");
        ProblemDraftWorkingState state = ProblemDraftWorkingState.fromRequest(request);
        AiConversationResponse conversation = conversationService.create(userId, new AiConversationCreateRequest(
                null,
                request.topic(),
                CONVERSATION_SOURCE,
                "workspace",
                workspaceId,
                "problem_draft"
        ));
        LocalDateTime now = LocalDateTime.now();
        ProblemDraftReviewRisk risk = riskService.derive(state.difficulty(), request.cfRating(), false);
        ProblemDraftWorkspaceEntity entity = new ProblemDraftWorkspaceEntity();
        entity.setId(workspaceId);
        entity.setCreatorUserId(userId);
        entity.setConversationId(conversation.conversationId());
        entity.setCandidateDraftId(IdWorker.getId());
        entity.setStatus(STATUS_QUEUED);
        entity.setStage("QUEUED");
        entity.setAgentMode("THINKING_TOOL_LOOP");
        entity.setStateVersion(0L);
        entity.setWorkingStateJson(toJson(state));
        entity.setRequestedCfRating(request.cfRating());
        entity.setReviewRisk(risk.code());
        entity.setContentFingerprint(fingerprintService.contentFingerprint(state, request.cfRating()));
        entity.setVerificationFingerprint(fingerprintService.verificationFingerprint(state, request.cfRating()));
        entity.setSafeSummary("AI problem draft workspace created");
        entity.setResourceMetricsJson("{}");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        workspaceMapper.insert(entity);
        return entity;
    }

    @Transactional
    public ProblemDraftWorkspaceEntity createForExistingDraft(Long userId, ProblemDraftResponse source) {
        if (source == null || source.id() == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Source problem draft is required");
        }
        ProblemDraftEntity sourceEntity = draftMapper.selectById(source.id());
        if (sourceEntity != null && sourceEntity.getWorkspaceId() != null) {
            return requireOwned(sourceEntity.getWorkspaceId(), userId);
        }
        String workspaceId = UUID.randomUUID().toString().replace("-", "");
        ProblemDraftWorkingState state = ProblemDraftWorkingState.fromDraft(source);
        AiConversationResponse conversation = conversationService.create(userId, new AiConversationCreateRequest(
                null, source.title(), CONVERSATION_SOURCE, "workspace", workspaceId, "problem_draft"));
        LocalDateTime now = LocalDateTime.now();
        ProblemDraftReviewRisk risk = riskService.derive(source.difficulty(), sourceEntity == null ? null : sourceEntity.getRequestedCfRating(), false);
        ProblemDraftWorkspaceEntity entity = new ProblemDraftWorkspaceEntity();
        entity.setId(workspaceId);
        entity.setCreatorUserId(userId);
        entity.setConversationId(conversation.conversationId());
        entity.setCandidateDraftId(IdWorker.getId());
        entity.setSourceDraftId(source.id());
        entity.setCurrentDraftId(source.id());
        entity.setStatus(STATUS_PENDING_REVIEW);
        entity.setStage("READY_FOR_OPTIMIZATION");
        entity.setAgentMode("STANDARD_TOOL_FALLBACK");
        entity.setStateVersion(0L);
        entity.setWorkingStateJson(toJson(state));
        entity.setRequestedCfRating(sourceEntity == null ? null : sourceEntity.getRequestedCfRating());
        entity.setReviewRisk(risk.code());
        entity.setContentFingerprint(fingerprintService.contentFingerprint(state, entity.getRequestedCfRating()));
        entity.setVerificationFingerprint(fingerprintService.verificationFingerprint(state, entity.getRequestedCfRating()));
        entity.setSafeSummary("Existing problem draft attached to an isolated Agent workspace");
        entity.setResourceMetricsJson("{}");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        workspaceMapper.insert(entity);
        if (sourceEntity != null) {
            sourceEntity.setWorkspaceId(workspaceId);
            sourceEntity.setContentFingerprint(entity.getContentFingerprint());
            sourceEntity.setVerificationFingerprint(entity.getVerificationFingerprint());
            sourceEntity.setReviewRisk(risk.code());
            draftMapper.updateById(sourceEntity);
        }
        return entity;
    }

    public ProblemDraftWorkspaceResponse get(String workspaceId) {
        return toResponse(requireVisible(workspaceId));
    }

    public ProblemDraftWorkspaceEntity requireOwned(String workspaceId, Long userId) {
        ProblemDraftWorkspaceEntity workspace = require(workspaceId);
        if (userId == null || !userId.equals(workspace.getCreatorUserId())) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft workspace not found");
        }
        return workspace;
    }

    public ProblemDraftWorkspaceEntity requireForTool(String workspaceId, long userId) {
        return requireOwned(workspaceId, userId);
    }

    public ProblemDraftWorkingState state(ProblemDraftWorkspaceEntity workspace) {
        try {
            return objectMapper.readValue(workspace.getWorkingStateJson(), ProblemDraftWorkingState.class);
        } catch (JacksonException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Problem draft workspace state is invalid");
        }
    }

    public Map<String, Object> stateForTool(String workspaceId, long userId) {
        ProblemDraftWorkspaceEntity workspace = requireForTool(workspaceId, userId);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("workspaceId", workspace.getId());
        value.put("stateVersion", workspace.getStateVersion());
        value.put("currentDraftId", workspace.getCurrentDraftId());
        value.put("state", state(workspace));
        value.put("contentFingerprint", workspace.getContentFingerprint());
        value.put("verificationFingerprint", workspace.getVerificationFingerprint());
        value.put("staticValidation", readJson(workspace.getStaticValidationJson()));
        value.put("samplesValidation", readJson(workspace.getSamplesValidationJson()));
        value.put("packageValidation", readJson(workspace.getPackageValidationJson()));
        value.put("artifact", artifactSummary(currentArtifact(workspace), workspace.getVerificationFingerprint()));
        return value;
    }

    @Transactional
    public PatchOutcome patch(String workspaceId, long userId, long expectedStateVersion, JsonNode patch) {
        ProblemDraftWorkspaceEntity workspace = requireForTool(workspaceId, userId);
        if (patch == null || !patch.isObject()) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "draft.state.patch requires an object patch");
        }
        if (workspace.getStateVersion() == null || workspace.getStateVersion() != expectedStateVersion) {
            throw new DomainException(ErrorCode.CONFLICT, "Problem draft workspace version conflict");
        }
        ObjectNode current = objectMapper.valueToTree(state(workspace));
        patch.propertyNames().forEach(field -> {
            if (!PATCHABLE_FIELDS.contains(field)) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Unsupported problem draft patch field: " + field);
            }
            current.set(field, patch.get(field));
        });
        ProblemDraftWorkingState next;
        try {
            next = objectMapper.treeToValue(current, ProblemDraftWorkingState.class);
        } catch (JacksonException ex) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Problem draft patch has invalid field types");
        }
        String oldVerificationFingerprint = workspace.getVerificationFingerprint();
        String contentFingerprint = fingerprintService.contentFingerprint(next, workspace.getRequestedCfRating());
        String verificationFingerprint = fingerprintService.verificationFingerprint(next, workspace.getRequestedCfRating());
        boolean verificationChanged = !verificationFingerprint.equals(oldVerificationFingerprint);
        long nextVersion = expectedStateVersion + 1;
        Long candidateDraftId = workspace.getCandidateDraftId();
        if (workspace.getCurrentDraftId() != null && workspace.getCurrentDraftId().equals(candidateDraftId)) {
            candidateDraftId = IdWorker.getId();
        }
        ProblemDraftReviewRisk risk = riskService.derive(next.difficulty(), workspace.getRequestedCfRating(), false);
        UpdateWrapper<ProblemDraftWorkspaceEntity> update = new UpdateWrapper<ProblemDraftWorkspaceEntity>()
                .eq("id", workspaceId)
                .eq("creator_user_id", userId)
                .eq("state_version", expectedStateVersion)
                .set("candidate_draft_id", candidateDraftId)
                .set("state_version", nextVersion)
                .set("working_state_json", toJson(next))
                .set("content_fingerprint", contentFingerprint)
                .set("verification_fingerprint", verificationFingerprint)
                .set("review_risk", risk.code())
                .set("status", STATUS_RUNNING)
                .set("stage", "DRAFT_UPDATED")
                .set("updated_at", LocalDateTime.now());
        if (verificationChanged) {
            update.set("static_validation_json", null)
                    .set("samples_validation_json", null)
                    .set("package_validation_json", null);
        }
        if (workspaceMapper.update(null, update) != 1) {
            throw new DomainException(ErrorCode.CONFLICT, "Problem draft workspace version conflict");
        }
        if (verificationChanged) {
            artifactMapper.update(null, new UpdateWrapper<ProblemDraftTestcaseArtifactEntity>()
                    .eq("workspace_id", workspaceId)
                    .ne("verification_fingerprint", verificationFingerprint)
                    .set("execution_status", "STALE")
                    .set("validated_at", LocalDateTime.now())
                    .set("updated_at", LocalDateTime.now()));
        }
        return new PatchOutcome(nextVersion, contentFingerprint, verificationFingerprint, verificationChanged);
    }

    /**
     * Applies a staff edit to the mutable workspace and snapshots it as a new
     * immutable draft version. Material changes invalidate all execution
     * evidence; the new version cannot be imported until the Agent gates pass.
     */
    @Transactional
    public ProblemDraftResponse applyHumanEdit(String workspaceId, long userId, Long expectedDraftId,
                                               ProblemDraftRefineRequest request) {
        if (request == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Problem draft edit is required");
        }
        ProblemDraftWorkspaceEntity workspace = requireOwned(workspaceId, userId);
        if (STATUS_RUNNING.equals(workspace.getStatus()) || STATUS_QUEUED.equals(workspace.getStatus())) {
            throw new DomainException(ErrorCode.CONFLICT,
                    "Wait for the current problem draft Agent turn before editing the workspace");
        }
        if (!java.util.Objects.equals(workspace.getCurrentDraftId(), expectedDraftId)) {
            throw new DomainException(ErrorCode.CONFLICT,
                    "Problem draft changed; refresh the workspace before editing it");
        }
        ProblemDraftEntity source = draftMapper.selectById(expectedDraftId);
        if (source == null || !workspaceId.equals(source.getWorkspaceId())) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft workspace version not found");
        }

        ObjectNode humanPatch = objectMapper.createObjectNode();
        setPatch(humanPatch, "title", request.title());
        setPatch(humanPatch, "difficulty", request.difficulty());
        setPatch(humanPatch, "statement", request.statement());
        setPatch(humanPatch, "notes", request.notes());
        setPatch(humanPatch, "standardSolutionLanguage", request.standardSolutionLanguage());
        setPatch(humanPatch, "standardSolutionCode", request.standardSolutionCode());
        setPatch(humanPatch, "referenceSolutionLanguage", request.referenceSolutionLanguage());
        setPatch(humanPatch, "referenceSolutionCode", request.referenceSolutionCode());
        setPatch(humanPatch, "testcaseGeneratorPython", request.testcaseGeneratorPython());
        setPatch(humanPatch, "stressTestcaseGeneratorPython", request.stressTestcaseGeneratorPython());
        setPatch(humanPatch, "generationPlan", request.generationPlan());
        setPatch(humanPatch, "tags", request.tags());
        setPatch(humanPatch, "testCases", request.testCases());
        setPatch(humanPatch, "timeLimitMillis", request.timeLimitMillis());
        setPatch(humanPatch, "memoryLimitKb", request.memoryLimitKb());

        PatchOutcome outcome = patch(workspaceId, userId, workspace.getStateVersion(), humanPatch);
        ValidationSnapshot staticSnapshot = staticValidate(workspaceId, userId);
        ProblemDraftWorkspaceEntity edited = requireOwned(workspaceId, userId);
        ProblemDraftResponse version = draftStore.createHumanWorkspaceVersion(
                userId, workspaceId, edited.getCandidateDraftId(), outcome.stateVersion(),
                edited.getRequestedCfRating(), edited.getReviewRisk(), outcome.contentFingerprint(),
                outcome.verificationFingerprint(), state(edited), staticSnapshot.report(), expectedDraftId,
                request.refineNote(), source.getModel());
        int updated = workspaceMapper.update(null, new UpdateWrapper<ProblemDraftWorkspaceEntity>()
                .eq("id", workspaceId)
                .eq("creator_user_id", userId)
                .eq("state_version", outcome.stateVersion())
                .set("current_draft_id", version.id())
                .set("status", STATUS_NEEDS_ATTENTION)
                .set("stage", "HUMAN_EDITED")
                .set("safe_summary", "Human edit saved as a new draft version; Agent validation is required")
                .set("updated_at", LocalDateTime.now()));
        if (updated != 1) {
            throw new DomainException(ErrorCode.CONFLICT, "Problem draft workspace changed during the edit");
        }
        return version;
    }

    @Transactional
    public ValidationSnapshot staticValidate(String workspaceId, long userId) {
        ProblemDraftWorkspaceEntity workspace = requireForTool(workspaceId, userId);
        ProblemDraftWorkingState state = state(workspace);
        boolean reference = riskService.derive(state.difficulty(), workspace.getRequestedCfRating(), false).highDifficulty();
        VerificationReport report = staticValidator.verify(
                state.toResponse(workspace.getCandidateDraftId(), null, 0, 0),
                new VerificationOptions(staticValidator.allowedTags(), state.difficulty(), workspace.getRequestedCfRating(), null, reference)
        );
        ValidationSnapshot snapshot = new ValidationSnapshot(workspace.getVerificationFingerprint(), report);
        workspaceMapper.update(null, new UpdateWrapper<ProblemDraftWorkspaceEntity>()
                .eq("id", workspaceId)
                .eq("creator_user_id", userId)
                .eq("state_version", workspace.getStateVersion())
                .set("static_validation_json", toJson(snapshot))
                .set("stage", report.passed() ? "STATIC_VALIDATED" : "STATIC_VALIDATION_FAILED")
                .set("updated_at", LocalDateTime.now()));
        return snapshot;
    }

    @Transactional
    public void storeSamplesValidation(String workspaceId, long userId, VerificationReport report,
                                       Map<String, Object> metrics) {
        ProblemDraftWorkspaceEntity workspace = requireForTool(workspaceId, userId);
        ValidationSnapshot snapshot = new ValidationSnapshot(workspace.getVerificationFingerprint(), report);
        workspaceMapper.update(null, new UpdateWrapper<ProblemDraftWorkspaceEntity>()
                .eq("id", workspaceId)
                .eq("creator_user_id", userId)
                .eq("state_version", workspace.getStateVersion())
                .set("samples_validation_json", toJson(snapshot))
                .set("resource_metrics_json", toJson(metrics == null ? Map.of() : metrics))
                .set("stage", report != null && report.passed() ? "SAMPLES_VALIDATED" : "SAMPLES_FAILED")
                .set("updated_at", LocalDateTime.now()));
    }

    /** Merge run-level counters with Sandbox metrics already collected for this workspace. */
    @Transactional
    public void storeAgentRunMetrics(String workspaceId, long userId, Map<String, Object> metrics) {
        ProblemDraftWorkspaceEntity workspace = requireForTool(workspaceId, userId);
        Map<String, Object> merged = new LinkedHashMap<>(readMap(workspace.getResourceMetricsJson()));
        if (metrics != null) {
            metrics.forEach((key, value) -> {
                if (key != null && value != null) {
                    merged.put(key, value);
                }
            });
        }
        if (!merged.containsKey("peakMemoryKb") && merged.get("maxMemoryKb") != null) {
            merged.put("peakMemoryKb", merged.get("maxMemoryKb"));
        }
        workspaceMapper.update(null, new UpdateWrapper<ProblemDraftWorkspaceEntity>()
                .eq("id", workspaceId)
                .eq("creator_user_id", userId)
                .set("resource_metrics_json", toJson(merged))
                .set("updated_at", LocalDateTime.now()));
    }

    @Transactional
    public void storeFinalizedAgentUsage(String workspaceId, long userId, Long draftId, Long agentRunId,
                                         String model, long promptTokens, long completionTokens) {
        ProblemDraftWorkspaceEntity workspace = requireForTool(workspaceId, userId);
        if (draftId == null || !draftId.equals(workspace.getCurrentDraftId())
                || agentRunId == null || !agentRunId.equals(workspace.getCurrentAgentRunId())) {
            throw new DomainException(ErrorCode.CONFLICT,
                    "Problem draft Agent usage does not match the finalized workspace version");
        }
        draftStore.updateFinalizedAgentUsage(userId, workspaceId, draftId, agentRunId, model,
                promptTokens, completionTokens);
    }

    @Transactional
    public void attachArtifact(String workspaceId, long userId, ProblemDraftTestcaseArtifactEntity artifact) {
        ProblemDraftWorkspaceEntity workspace = requireForTool(workspaceId, userId);
        if (artifact == null || !workspaceId.equals(artifact.getWorkspaceId())) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Testcase artifact does not belong to the workspace");
        }
        workspaceMapper.update(null, new UpdateWrapper<ProblemDraftWorkspaceEntity>()
                .eq("id", workspaceId)
                .eq("creator_user_id", userId)
                .set("current_artifact_id", artifact.getId())
                .set("stage", "TESTCASE_PACKAGE_READY")
                .set("updated_at", LocalDateTime.now()));
    }

    @Transactional
    public void storePackageValidation(String workspaceId, long userId, Long artifactId,
                                       boolean passed, Map<String, Object> safeResult) {
        ProblemDraftWorkspaceEntity workspace = requireForTool(workspaceId, userId);
        ProblemDraftTestcaseArtifactEntity artifact = artifactMapper.selectById(artifactId);
        if (artifact == null || !workspaceId.equals(artifact.getWorkspaceId())) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft testcase artifact not found");
        }
        String executionStatus = passed ? "PASSED" : "FAILED";
        artifact.setExecutionStatus(executionStatus);
        artifact.setVerificationMode("AGENT_VERIFIED");
        artifact.setValidatedAt(LocalDateTime.now());
        artifact.setFailureSummary(passed ? null : truncate(String.valueOf(safeResult), 1000));
        artifact.setUpdatedAt(LocalDateTime.now());
        artifactMapper.updateById(artifact);
        Map<String, Object> packageSnapshot = new LinkedHashMap<>();
        packageSnapshot.put("fingerprint", workspace.getVerificationFingerprint());
        packageSnapshot.put("artifactId", artifactId);
        packageSnapshot.put("passed", passed);
        packageSnapshot.put("result", safeResult == null ? Map.of() : safeResult);
        workspaceMapper.update(null, new UpdateWrapper<ProblemDraftWorkspaceEntity>()
                .eq("id", workspaceId)
                .eq("creator_user_id", userId)
                .set("package_validation_json", toJson(packageSnapshot))
                .set("stage", passed ? "PACKAGE_VALIDATED" : "PACKAGE_VALIDATION_FAILED")
                .set("updated_at", LocalDateTime.now()));
    }

    @Transactional
    public ProblemDraftResponse finalizeCurrent(String workspaceId, long userId, Long agentRunId,
                                                String model, long promptTokens, long completionTokens) {
        ProblemDraftWorkspaceEntity workspace = requireForTool(workspaceId, userId);
        ProblemDraftWorkingState state = state(workspace);
        ValidationSnapshot staticSnapshot = readValidation(workspace.getStaticValidationJson());
        ValidationSnapshot sampleSnapshot = readValidation(workspace.getSamplesValidationJson());
        ProblemDraftTestcaseArtifactEntity artifact = currentArtifact(workspace);
        assertCurrentPassed(workspace, staticSnapshot, "Static validation has not passed for the current content");
        assertCurrentPassed(workspace, sampleSnapshot, "Sample execution has not passed for the current content");
        if (artifact == null || !"READY".equals(artifact.getFormatStatus())
                || !"PASSED".equals(artifact.getExecutionStatus())
                || !workspace.getVerificationFingerprint().equals(artifact.getVerificationFingerprint())) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED,
                    "Full testcase package acceptance has not passed for the current content");
        }
        ProblemDraftResponse finalized = draftStore.finalizeAgentWorkspace(
                userId, workspaceId, workspace.getCandidateDraftId(), workspace.getStateVersion(), agentRunId,
                workspace.getRequestedCfRating(), workspace.getReviewRisk(), workspace.getContentFingerprint(),
                workspace.getVerificationFingerprint(), state, staticSnapshot.report(),
                toJson(Map.of("status", "EXECUTION_VERIFIED", "artifactId", artifact.getId(),
                        "verificationFingerprint", workspace.getVerificationFingerprint())),
                model, promptTokens, completionTokens
        );
        workspaceMapper.update(null, new UpdateWrapper<ProblemDraftWorkspaceEntity>()
                .eq("id", workspaceId)
                .eq("creator_user_id", userId)
                .eq("state_version", workspace.getStateVersion())
                .set("current_draft_id", finalized.id())
                .set("current_agent_run_id", agentRunId)
                .set("status", STATUS_PENDING_REVIEW)
                .set("stage", "FINALIZED")
                .set("safe_summary", "Draft finalized after static, sample, and full package validation")
                .set("updated_at", LocalDateTime.now()));
        return finalized;
    }

    @Transactional
    public void bindRun(String workspaceId, long userId, String turnId, Long runId, String agentMode,
                        String status, String stage, String safeSummary) {
        requireForTool(workspaceId, userId);
        workspaceMapper.update(null, new UpdateWrapper<ProblemDraftWorkspaceEntity>()
                .eq("id", workspaceId)
                .eq("creator_user_id", userId)
                .set("active_turn_id", turnId)
                .set("current_agent_run_id", runId)
                .set("agent_mode", agentMode)
                .set("status", status)
                .set("stage", stage)
                .set("safe_summary", truncate(safeSummary, 1000))
                .set("updated_at", LocalDateTime.now()));
    }

    /**
     * Atomically reserves the workspace for one durable Agent turn. A workspace may only have
     * one queued/running turn at a time; different clientTurnIds racing on the same workspace
     * therefore cannot both reach the provider. The initial generation starts from QUEUED with
     * no active turn, while later optimization turns start from a terminal workspace state.
     */
    @Transactional
    public void reserveTurn(String workspaceId, long userId, String turnId, boolean initialTurn) {
        ProblemDraftWorkspaceEntity workspace = requireOwned(workspaceId, userId);
        if (turnId != null && turnId.equals(workspace.getActiveTurnId())) {
            return;
        }
        UpdateWrapper<ProblemDraftWorkspaceEntity> update = new UpdateWrapper<ProblemDraftWorkspaceEntity>()
                .eq("id", workspaceId)
                .eq("creator_user_id", userId);
        if (initialTurn) {
            update.eq("status", STATUS_QUEUED).isNull("active_turn_id");
        } else {
            update.in("status", List.of(STATUS_PENDING_REVIEW, STATUS_NEEDS_ATTENTION));
        }
        update.set("active_turn_id", turnId)
                .set("status", STATUS_QUEUED)
                .set("stage", "TURN_QUEUED")
                .set("safe_summary", "Problem draft Agent turn queued")
                .set("updated_at", LocalDateTime.now());
        if (workspaceMapper.update(null, update) != 1) {
            throw new DomainException(ErrorCode.CONFLICT,
                    "Another problem draft Agent turn is already queued or running");
        }
    }

    public PageResponse<ProblemDraftWorkspaceMessageResponse> messages(String workspaceId, long page, long pageSize) {
        ProblemDraftWorkspaceEntity workspace = requireVisible(workspaceId);
        long current = Math.max(1, page);
        long size = Math.min(Math.max(1, pageSize), 100);
        long offset = (current - 1) * size;
        QueryWrapper<AiMessageEntity> count = new QueryWrapper<AiMessageEntity>()
                .eq("conversation_id", workspace.getConversationId());
        long total = messageMapper.selectCount(count);
        List<ProblemDraftWorkspaceMessageResponse> records = messageMapper.selectList(
                        new QueryWrapper<AiMessageEntity>()
                                .eq("conversation_id", workspace.getConversationId())
                                .orderByAsc("created_at")
                                .last("LIMIT " + size + " OFFSET " + offset))
                .stream()
                .map(message -> new ProblemDraftWorkspaceMessageResponse(
                        message.getId(), workspaceId,
                        message.getRole() == null ? "SYSTEM" : message.getRole().toUpperCase(Locale.ROOT),
                        message.getContent(), message.getStatus(),
                        message.getModel(), toInstant(message.getCreatedAt()), toInstant(message.getCompletedAt())))
                .toList();
        return new PageResponse<>(records, total, current, size);
    }

    public List<ProblemDraftAgentRunResponse> agentRuns(String workspaceId, Long afterId) {
        ProblemDraftWorkspaceEntity workspace = requireVisible(workspaceId);
        QueryWrapper<AiAgentRunEntity> query = new QueryWrapper<AiAgentRunEntity>()
                .eq("workspace_id", workspace.getId())
                .orderByAsc("id")
                .last("LIMIT 100");
        if (afterId != null) {
            query.gt("id", afterId);
        }
        return agentRunMapper.selectList(query).stream().map(run -> new ProblemDraftAgentRunResponse(
                run.getId(), workspaceId, run.getStatus(), run.getExecutionMode(), run.getProvider(), run.getModel(),
                run.getStepCount(), run.getToolCallCount(), run.getSafeSummary(), run.getErrorCode(),
                toInstant(run.getStartedAt()), toInstant(run.getCompletedAt())
        )).toList();
    }

    public List<ProblemDraftToolCallResponse> toolCalls(String workspaceId, Long afterId) {
        ProblemDraftWorkspaceEntity workspace = requireVisible(workspaceId);
        List<Long> runIds = agentRunMapper.selectList(new QueryWrapper<AiAgentRunEntity>()
                        .select("id")
                        .eq("workspace_id", workspace.getId()))
                .stream().map(AiAgentRunEntity::getId).toList();
        if (runIds.isEmpty()) {
            return List.of();
        }
        QueryWrapper<AiToolCallEntity> query = new QueryWrapper<AiToolCallEntity>()
                .in("agent_run_id", runIds)
                .orderByAsc("id")
                .last("LIMIT 200");
        if (afterId != null) {
            query.gt("id", afterId);
        }
        return toolCallMapper.selectList(query).stream().map(call -> new ProblemDraftToolCallResponse(
                call.getId(), call.getAgentRunId(), call.getCallSeq(), call.getToolName(), call.getStatus(),
                call.getResultClassification(), safeSummaryText(call.getSafeSummaryJson()), call.getResourceRefType(),
                call.getResourceRefId(), JsonLongIds.stringifyMap(readMap(call.getResourceMetricsJson())),
                call.getLatencyMs(), call.getErrorCode(),
                toInstant(call.getStartedAt() == null ? call.getCreatedAt() : call.getStartedAt()),
                toInstant(call.getCompletedAt())
        )).toList();
    }

    public ProblemDraftWorkspaceEntity require(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft workspace not found");
        }
        ProblemDraftWorkspaceEntity workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft workspace not found");
        }
        return workspace;
    }

    private ProblemDraftWorkspaceEntity requireVisible(String workspaceId) {
        ProblemDraftWorkspaceEntity workspace = require(workspaceId);
        Long currentUserId = SecuritySupport.currentUserId();
        if (!currentUserId.equals(workspace.getCreatorUserId()) && !SecuritySupport.hasRole(Role.ADMIN)) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft workspace not found");
        }
        return workspace;
    }

    private void assertCurrentPassed(ProblemDraftWorkspaceEntity workspace, ValidationSnapshot snapshot, String message) {
        if (snapshot == null || snapshot.report() == null || !snapshot.report().passed()
                || !workspace.getVerificationFingerprint().equals(snapshot.fingerprint())) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, message);
        }
    }

    private ValidationSnapshot readValidation(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ValidationSnapshot.class);
        } catch (JacksonException ex) {
            return null;
        }
    }

    private void setPatch(ObjectNode patch, String field, Object value) {
        if (value != null) {
            patch.set(field, objectMapper.valueToTree(value));
        }
    }

    private ProblemDraftTestcaseArtifactEntity currentArtifact(ProblemDraftWorkspaceEntity workspace) {
        if (workspace.getCurrentArtifactId() != null) {
            ProblemDraftTestcaseArtifactEntity direct = artifactMapper.selectById(workspace.getCurrentArtifactId());
            if (direct != null && workspace.getId().equals(direct.getWorkspaceId())) {
                return direct;
            }
        }
        return artifactMapper.selectOne(new QueryWrapper<ProblemDraftTestcaseArtifactEntity>()
                .eq("workspace_id", workspace.getId())
                .notIn("status", List.of("SUPERSEDED", "FAILED"))
                .orderByDesc("created_at")
                .last("LIMIT 1"));
    }

    private ProblemDraftWorkspaceResponse toResponse(ProblemDraftWorkspaceEntity workspace) {
        ProblemDraftTestcaseArtifactEntity artifact = currentArtifact(workspace);
        ProblemDraftEntity draft = workspace.getCurrentDraftId() == null ? null : draftMapper.selectById(workspace.getCurrentDraftId());
        ProblemDraftWorkingState workingState = state(workspace);
        boolean manual = artifact != null && "MANUAL_UNVERIFIED".equals(artifact.getVerificationMode());
        ProblemDraftReviewRisk reviewRisk = riskService.derive(workingState.difficulty(), workspace.getRequestedCfRating(), manual);
        ProblemDraftTestcaseArtifactSummary artifactSummary = artifactSummary(artifact, workspace.getVerificationFingerprint());
        ProblemDraftImportReadiness readiness = importReadiness(draft, artifact, workspace.getVerificationFingerprint());
        return new ProblemDraftWorkspaceResponse(
                workspace.getId(), workspace.getCreatorUserId(), workspace.getConversationId(), workspace.getSourceDraftId(),
                workspace.getCurrentDraftId(), workspace.getCurrentAgentRunId(), workingState.title(), workspace.getStatus(), workspace.getStage(),
                workspace.getAgentMode(), workspace.getStateVersion(), workspace.getRequestedCfRating(), reviewRisk,
                workspace.getContentFingerprint(), workspace.getVerificationFingerprint(), workspace.getSafeSummary(),
                readMap(workspace.getResourceMetricsJson()), artifactSummary, readiness,
                toInstant(workspace.getCreatedAt()), toInstant(workspace.getUpdatedAt())
        );
    }

    private ProblemDraftImportReadiness importReadiness(ProblemDraftEntity draft,
                                                        ProblemDraftTestcaseArtifactEntity artifact,
                                                        String fingerprint) {
        List<String> blockers = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        if (draft == null) {
            blockers.add("DRAFT_NOT_FINALIZED");
            actions.add("FINALIZE_DRAFT");
        } else {
            if (!"APPROVED".equals(draft.getStatus())) {
                blockers.add("DRAFT_NOT_APPROVED");
                actions.add("APPROVE_CONTENT");
            }
            if (!"VALID".equals(draft.getValidationStatus())) {
                blockers.add("STATIC_CONTENT_INVALID");
                actions.add("FIX_STATIC_VALIDATION");
            }
        }
        if (artifact == null) {
            blockers.add("TESTCASE_PACKAGE_MISSING");
            actions.add("GENERATE_OR_UPLOAD_PACKAGE");
        } else {
            if (!"READY".equals(artifact.getFormatStatus())) {
                blockers.add("TESTCASE_FORMAT_NOT_READY");
                actions.add("FIX_PACKAGE_FORMAT");
            }
            if (!fingerprint.equals(artifact.getVerificationFingerprint())) {
                blockers.add("TESTCASE_ARTIFACT_STALE");
                actions.add("REGENERATE_OR_REUPLOAD_PACKAGE");
            }
        }
        boolean verified = artifact != null && "PASSED".equals(artifact.getExecutionStatus());
        boolean requiresAck = artifact != null && !verified;
        String mode = verified ? "AGENT_VERIFIED" : requiresAck ? "MANUAL_UNVERIFIED" : null;
        return new ProblemDraftImportReadiness(blockers.isEmpty(), requiresAck, mode, blockers, actions);
    }

    private ProblemDraftTestcaseArtifactSummary artifactSummary(ProblemDraftTestcaseArtifactEntity artifact,
                                                                 String currentFingerprint) {
        if (artifact == null) {
            return null;
        }
        boolean stale = currentFingerprint == null || !currentFingerprint.equals(artifact.getVerificationFingerprint())
                || "STALE".equals(artifact.getExecutionStatus());
        return new ProblemDraftTestcaseArtifactSummary(
                artifact.getId(), valueOr(artifact.getSource(), "AGENT_GENERATED"), artifact.getStatus(),
                valueOr(artifact.getFormatStatus(), "READY"), valueOr(artifact.getExecutionStatus(), "NOT_RUN"),
                artifact.getVerificationMode(), artifact.getVerificationFingerprint(), stale, artifact.getCaseCount(),
                artifact.getFileSizeBytes(), artifact.getSha256(),
                UserErrorFeedback.storedKey(artifact.getFailureSummary(), "testcase.processFailed"), artifact.getAgentRunId(),
                toInstant(artifact.getGeneratedAt()), toInstant(artifact.getValidatedAt()),
                artifact.getImportedProblemId(), artifact.getProblemTestcasePackageId());
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JacksonException ex) {
            return Map.of("status", "UNREADABLE");
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() { });
            return parsed == null ? Map.of() : parsed;
        } catch (JacksonException ex) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "Problem draft workspace value could not be serialized");
        }
    }

    private Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZONE).toInstant();
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String safeSummaryText(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String status = node.path("status").asString("UNKNOWN");
            String classification = node.path("classification").asString("");
            return classification.isBlank() ? status : status + " · " + classification;
        } catch (JacksonException ex) {
            return null;
        }
    }

    public record PatchOutcome(long stateVersion, String contentFingerprint, String verificationFingerprint,
                               boolean verificationInvalidated) {
    }

    public record ValidationSnapshot(String fingerprint, VerificationReport report) {
    }
}
