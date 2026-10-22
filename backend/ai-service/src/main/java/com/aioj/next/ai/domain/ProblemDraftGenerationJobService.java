package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.domain.draftagent.ProblemDraftAgentCoordinator;
import com.aioj.next.ai.domain.draftagent.ProblemDraftReviewRiskService;
import com.aioj.next.ai.domain.draftagent.ProblemDraftWorkspaceService;
import com.aioj.next.ai.persistence.entity.ProblemDraftGenerationJobEntity;
import com.aioj.next.ai.persistence.entity.ProblemDraftWorkspaceEntity;
import com.aioj.next.ai.persistence.mapper.ProblemDraftGenerationJobMapper;
import com.aioj.next.common.api.PageResponse;
import com.aioj.next.common.error.DomainException;
import com.aioj.next.common.error.ErrorCode;
import com.aioj.next.common.security.Role;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.ai.ProblemDraftAgentTurnRequest;
import com.aioj.next.contract.ai.ProblemDraftAgentTurnResponse;
import com.aioj.next.contract.ai.ProblemDraftGenerationJobResponse;
import com.aioj.next.contract.ai.ProblemDraftRegenerateRequest;
import com.aioj.next.contract.ai.ProblemDraftRequest;
import com.aioj.next.contract.ai.ProblemDraftResponse;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Durable queue for model-led problem-draft Agent turns. */
@Service
public class ProblemDraftGenerationJobService {
    private static final Logger log = LoggerFactory.getLogger(ProblemDraftGenerationJobService.class);
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final int PROGRESS_TOTAL = 20;
    private static final int PAGE_SIZE_MAX = 100;
    private static final int WORKER_BATCH_SIZE = 8;
    private static final int ERROR_MESSAGE_MAX = 1000;

    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_NEEDS_ATTENTION = "NEEDS_ATTENTION";
    private static final String STATUS_FAILED = "FAILED"; // legacy rows remain readable
    private static final String STATUS_CANCELLED = "CANCELLED";

    private static final String JOB_TYPE_GENERATE = "GENERATE";
    private static final String JOB_TYPE_REGENERATE = "REGENERATE";
    private static final String JOB_TYPE_OPTIMIZE = "OPTIMIZE";
    private static final String AUDIT_RESOURCE_TYPE = "AI_PROBLEM_DRAFT_GENERATION_JOB";

    private final ProblemDraftGenerationJobMapper jobMapper;
    private final ProblemDraftStore problemDraftStore;
    private final ProblemDraftAgentCoordinator coordinator;
    private final ProblemDraftWorkspaceService workspaceService;
    private final ProblemDraftReviewRiskService riskService;
    private final ObjectMapper objectMapper;
    private final OperationAuditWriter auditWriter;
    private final Executor problemDraftExecutor;
    private final TransactionTemplate requiresNew;
    private final AiProperties aiProperties;
    private final String workerId = "ai-draft-agent-job-" + UUID.randomUUID();
    /** Jobs currently executing on this instance; lease recovery must not touch them. */
    private final Set<Long> inFlightJobs = ConcurrentHashMap.newKeySet();

    public ProblemDraftGenerationJobService(
            ProblemDraftGenerationJobMapper jobMapper,
            ProblemDraftStore problemDraftStore,
            ProblemDraftAgentCoordinator coordinator,
            ProblemDraftWorkspaceService workspaceService,
            ProblemDraftReviewRiskService riskService,
            ObjectMapper objectMapper,
            OperationAuditWriter auditWriter,
            @Qualifier("aiProblemDraftExecutor") Executor problemDraftExecutor,
            PlatformTransactionManager transactionManager,
            AiProperties aiProperties
    ) {
        this.jobMapper = jobMapper;
        this.problemDraftStore = problemDraftStore;
        this.coordinator = coordinator;
        this.workspaceService = workspaceService;
        this.riskService = riskService;
        this.objectMapper = objectMapper;
        this.auditWriter = auditWriter;
        this.problemDraftExecutor = problemDraftExecutor;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.aiProperties = aiProperties;
    }

    @Transactional
    public ProblemDraftGenerationJobResponse create(Long userId, ProblemDraftRequest request) {
        if (userId == null || request == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Problem draft request is required");
        }
        ProblemDraftAgentCoordinator.PreparedTurn prepared = coordinator.prepareInitial(userId, request);
        ProblemDraftGenerationJobEntity job = newJob(userId, JOB_TYPE_GENERATE, null, prepared,
                request, request.topic(), "Queued for AI problem draft Agent generation");
        persistQueued(job, userId);
        return toResponse(job);
    }

    /**
     * Compatibility bridge for the retired synchronous and SSE generation endpoints. The work is
     * still represented by the same durable workspace, turn and job; this method merely waits for
     * that job so older clients can receive their historical response shape.
     */
    public ProblemDraftResponse awaitCompatibilityResult(Long userId, Long jobId) {
        ProblemDraftGenerationJobEntity initial = requireJob(jobId);
        if (userId == null || !userId.equals(initial.getCreatorUserId())) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft generation job not found");
        }
        process(jobId);
        long deadlineNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(16);
        while (true) {
            ProblemDraftGenerationJobEntity current = requireJob(jobId);
            if (STATUS_SUCCEEDED.equals(current.getStatus()) && current.getDraftId() != null) {
                return problemDraftStore.get(current.getDraftId());
            }
            if (STATUS_NEEDS_ATTENTION.equals(current.getStatus()) || STATUS_FAILED.equals(current.getStatus())
                    || STATUS_CANCELLED.equals(current.getStatus())) {
                throw new DomainException(ErrorCode.VALIDATION_FAILED,
                        valueOr(current.getErrorMessage(),
                                "Problem draft Agent needs attention; continue from the preserved workspace"));
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE,
                        "Problem draft Agent is still running; continue from the persistent generation job");
            }
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE,
                        "Problem draft generation wait was interrupted; the persistent job was preserved");
            }
        }
    }

    @Transactional
    public ProblemDraftGenerationJobResponse createRegeneration(Long userId, Long sourceDraftId,
                                                                ProblemDraftRegenerateRequest request) {
        if (userId == null || sourceDraftId == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Source problem draft id is required");
        }
        if (request == null) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Problem draft rewrite request is required");
        }
        ProblemDraftResponse source = problemDraftStore.regenerationSource(sourceDraftId);
        ProblemDraftAgentCoordinator.PreparedTurn prepared = coordinator.prepareRegeneration(userId, source, request);
        ProblemDraftGenerationJobEntity job = newJob(userId, JOB_TYPE_REGENERATE, sourceDraftId, prepared,
                request, "Rewrite: " + source.title(), "Queued for AI problem draft Agent rewrite");
        persistQueued(job, userId);
        return toResponse(job);
    }

    @Transactional
    public ProblemDraftAgentTurnResponse continueOptimization(Long userId, String workspaceId,
                                                              ProblemDraftAgentTurnRequest request) {
        ProblemDraftAgentCoordinator.PreparedTurn prepared = coordinator.prepareContinuation(
                userId, workspaceId, request);
        if (prepared.idempotentReplay()) {
            ProblemDraftGenerationJobEntity existing = findByTurn(prepared.turn().getId());
            if (existing == null) {
                throw new DomainException(ErrorCode.CONFLICT,
                        "The idempotent Agent turn is still being prepared; retry shortly");
            }
            ProblemDraftWorkspaceEntity current = workspaceService.requireOwned(workspaceId, userId);
            return turnResponse(current, existing, true);
        }
        ProblemDraftWorkspaceEntity workspace = prepared.workspace();
        Long sourceDraftId = workspace.getSourceDraftId() == null
                ? workspace.getCurrentDraftId() : workspace.getSourceDraftId();
        String workspaceTitle = workspaceService.state(workspace).title();
        ProblemDraftGenerationJobEntity job = newJob(userId, JOB_TYPE_OPTIMIZE, sourceDraftId, prepared,
                request, valueOr(workspaceTitle, "Continue problem draft"),
                "Queued for continued Agent optimization");
        persistQueued(job, userId);
        return turnResponse(workspace, job, false);
    }

    public PageResponse<ProblemDraftGenerationJobResponse> list(long page, long pageSize, String status,
                                                                 Long creatorUserId) {
        boolean admin = SecuritySupport.hasRole(Role.ADMIN);
        long current = Math.max(1, page);
        long size = Math.min(Math.max(1, pageSize), PAGE_SIZE_MAX);
        long offset = (current - 1) * size;
        QueryWrapper<ProblemDraftGenerationJobEntity> countQuery = new QueryWrapper<>();
        applyListFilters(countQuery, admin, creatorUserId, status);
        long total = jobMapper.selectCount(countQuery);
        QueryWrapper<ProblemDraftGenerationJobEntity> pageQuery = new QueryWrapper<>();
        applyListFilters(pageQuery, admin, creatorUserId, status);
        pageQuery.orderByDesc("updated_at").orderByDesc("created_at")
                .last("LIMIT " + size + " OFFSET " + offset);
        List<ProblemDraftGenerationJobResponse> records = jobMapper.selectList(pageQuery).stream()
                .map(this::toResponse).toList();
        return new PageResponse<>(records, total, current, size);
    }

    public ProblemDraftGenerationJobResponse get(Long id) {
        ProblemDraftGenerationJobEntity job = requireJob(id);
        boolean admin = SecuritySupport.hasRole(Role.ADMIN);
        Long currentUserId = SecuritySupport.currentUserId();
        if (!admin && !currentUserId.equals(job.getCreatorUserId())) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft generation job not found");
        }
        return toResponse(job);
    }

    @Scheduled(fixedDelayString = "${aioj.ai.problem-draft.generation-job-poll-ms:2000}")
    public void pollQueuedJobs() {
        recoverExpiredRunningJobs();
        List<ProblemDraftGenerationJobEntity> queued = jobMapper.selectList(
                new QueryWrapper<ProblemDraftGenerationJobEntity>()
                        .eq("status", STATUS_QUEUED)
                        .orderByAsc("created_at")
                        .last("LIMIT " + WORKER_BATCH_SIZE));
        for (ProblemDraftGenerationJobEntity job : queued) {
            if (!claim(job.getId())) {
                continue;
            }
            try {
                problemDraftExecutor.execute(() -> processClaimed(job.getId()));
            } catch (RejectedExecutionException ex) {
                requeue(job.getId(), "Waiting for AI Agent capacity");
                break;
            }
        }
    }

    void process(Long jobId) {
        if (claim(jobId)) {
            processClaimed(jobId);
        }
    }

    private void processClaimed(Long jobId) {
        inFlightJobs.add(jobId);
        try {
            ProblemDraftGenerationJobEntity job = jobMapper.selectById(jobId);
            if (job == null) {
                return;
            }
            try {
                workspaceService.bindRun(job.getWorkspaceId(), job.getCreatorUserId(), job.getTurnId(), null,
                        valueOr(job.getAgentMode(), "THINKING_TOOL_LOOP"),
                        ProblemDraftWorkspaceService.STATUS_RUNNING, "AGENT_RUNNING",
                        "Problem draft Agent is using controlled tools");
                ProblemDraftAgentCoordinator.AgentOutcome outcome = coordinator.execute(job);
                if (outcome.succeeded()) {
                    complete(jobId, outcome);
                } else {
                    needsAttention(jobId, outcome, null, null,
                            "Agent stopped before every finalization gate passed");
                }
            } catch (DomainException ex) {
                needsAttention(jobId, null, ex.errorCode().code(), errorKey(ex.errorCode()), safeMessage(ex));
            } catch (RuntimeException ex) {
                log.warn("Problem draft Agent job {} failed: {}", jobId, ex.toString(), ex);
                needsAttention(jobId, null, ErrorCode.INTERNAL_ERROR.code(), errorKey(ErrorCode.INTERNAL_ERROR),
                        safeMessage(ex));
            }
        } finally {
            inFlightJobs.remove(jobId);
        }
    }

    private ProblemDraftGenerationJobEntity newJob(long userId, String jobType, Long sourceDraftId,
                                                    ProblemDraftAgentCoordinator.PreparedTurn prepared,
                                                    Object request, String topic, String progressMessage) {
        ProblemDraftWorkspaceEntity workspace = prepared.workspace();
        LocalDateTime now = LocalDateTime.now();
        ProblemDraftGenerationJobEntity job = new ProblemDraftGenerationJobEntity();
        job.setCreatorUserId(userId);
        job.setJobType(jobType);
        job.setSourceDraftId(sourceDraftId);
        job.setWorkspaceId(workspace.getId());
        job.setTurnId(prepared.turn().getId());
        job.setStateVersion(workspace.getStateVersion());
        job.setRequestedCfRating(workspace.getRequestedCfRating());
        job.setReviewRisk(workspace.getReviewRisk());
        job.setAgentMode(valueOr(workspace.getAgentMode(), "THINKING_TOOL_LOOP"));
        job.setContentFingerprint(workspace.getContentFingerprint());
        job.setVerificationFingerprint(workspace.getVerificationFingerprint());
        job.setStatus(STATUS_QUEUED);
        job.setStage("QUEUED");
        job.setRequestJson(toJson(request));
        job.setTopicSnapshot(truncate(topic, 255));
        job.setProgressCurrent(0);
        job.setProgressTotal(PROGRESS_TOTAL);
        job.setProgressMessage(progressMessage);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        return job;
    }

    private void persistQueued(ProblemDraftGenerationJobEntity job, Long actorUserId) {
        jobMapper.insert(job);
        auditWriter.record(auditAction(job, "CREATED"), AUDIT_RESOURCE_TYPE, job.getId(), STATUS_QUEUED,
                auditSummary(job, null, null, null, null, null), actorUserId,
                null, null, null);
    }

    private boolean claim(Long jobId) {
        return Boolean.TRUE.equals(requiresNew.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            ProblemDraftGenerationJobEntity update = new ProblemDraftGenerationJobEntity();
            update.setStatus(STATUS_RUNNING);
            update.setStage("AGENT_RUNNING");
            update.setProgressCurrent(1);
            update.setProgressTotal(PROGRESS_TOTAL);
            update.setProgressMessage("Starting model-led problem draft Agent");
            update.setLeaseOwner(workerId);
            update.setLeaseExpiresAt(now.plusMinutes(leaseMinutes()));
            update.setStartedAt(now);
            update.setUpdatedAt(now);
            if (jobMapper.update(update, new UpdateWrapper<ProblemDraftGenerationJobEntity>()
                    .eq("id", jobId).eq("status", STATUS_QUEUED)) != 1) {
                return false;
            }
            ProblemDraftGenerationJobEntity current = jobMapper.selectById(jobId);
            if (current != null) {
                replaceAudit(current, "RUNNING", STATUS_RUNNING,
                        auditSummary(current, "startedAt", now, null, null, null));
            }
            return true;
        }));
    }

    private void requeue(Long jobId, String message) {
        requiresNew.executeWithoutResult(status -> {
            ProblemDraftGenerationJobEntity current = jobMapper.selectById(jobId);
            ProblemDraftGenerationJobEntity update = new ProblemDraftGenerationJobEntity();
            update.setStatus(STATUS_QUEUED);
            update.setStage("QUEUED");
            update.setProgressCurrent(0);
            update.setProgressTotal(PROGRESS_TOTAL);
            update.setProgressMessage(truncate(message, 255));
            update.setUpdatedAt(LocalDateTime.now());
            UpdateWrapper<ProblemDraftGenerationJobEntity> wrapper = new UpdateWrapper<ProblemDraftGenerationJobEntity>()
                    .set("lease_owner", null).set("lease_expires_at", null)
                    .set("started_at", null).eq("id", jobId).eq("status", STATUS_RUNNING);
            if (jobMapper.update(update, wrapper) == 1 && current != null) {
                replaceAudit(current, "CREATED", STATUS_QUEUED,
                        auditSummary(current, null, null, null, null, null));
            }
        });
    }

    private void complete(Long jobId, ProblemDraftAgentCoordinator.AgentOutcome outcome) {
        requiresNew.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            ProblemDraftGenerationJobEntity current = jobMapper.selectById(jobId);
            ProblemDraftGenerationJobEntity update = outcomeUpdate(outcome);
            update.setStatus(STATUS_SUCCEEDED);
            update.setStage("SUCCEEDED");
            update.setProgressCurrent(PROGRESS_TOTAL);
            update.setProgressTotal(PROGRESS_TOTAL);
            update.setProgressMessage("Problem draft Agent finalized a draft for human review");
            update.setDraftId(outcome.draftId());
            update.setCompletedAt(now);
            update.setUpdatedAt(now);
            UpdateWrapper<ProblemDraftGenerationJobEntity> wrapper = terminalWrapper(jobId);
            int updated = jobMapper.update(update, wrapper);
            if (updated != 1) {
                log.warn("Problem draft Agent job {} terminal completion matched {} rows; current status={}",
                        jobId, updated, current == null ? null : current.getStatus());
            } else if (current != null) {
                replaceAudit(current, "COMPLETED", "COMPLETED",
                        auditSummary(current, "completedAt", now, outcome.draftId(), null, null));
            }
        });
    }

    private void needsAttention(Long jobId, ProblemDraftAgentCoordinator.AgentOutcome outcome,
                                Integer errorCode, String errorKey, String errorMessage) {
        requiresNew.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            ProblemDraftGenerationJobEntity current = jobMapper.selectById(jobId);
            ProblemDraftGenerationJobEntity update = outcomeUpdate(outcome);
            Long runId = outcome == null && current != null
                    ? coordinator.runIdForTurn(current.getTurnId()) : outcome == null ? null : outcome.runId();
            if (runId != null) {
                update.setAgentRunId(runId);
            }
            update.setStatus(STATUS_NEEDS_ATTENTION);
            update.setStage(outcome == null ? "NEEDS_ATTENTION" : outcome.stage());
            update.setProgressMessage("Problem draft Agent needs attention; working state was preserved");
            update.setErrorCode(errorCode);
            update.setErrorKey(truncate(errorKey, 128));
            update.setErrorMessage(truncate(valueOr(errorMessage,
                    "Continue optimization from the preserved workspace state"), ERROR_MESSAGE_MAX));
            update.setCompletedAt(now);
            update.setUpdatedAt(now);
            int updated = jobMapper.update(update, terminalWrapper(jobId));
            if (updated != 1) {
                log.warn("Problem draft Agent job {} terminal needs-attention matched {} rows; current status={}",
                        jobId, updated, current == null ? null : current.getStatus());
            } else if (current != null) {
                replaceAudit(current, "NEEDS_ATTENTION", STATUS_NEEDS_ATTENTION,
                        auditSummary(current, "completedAt", now,
                                outcome == null ? null : outcome.draftId(), errorCode, errorKey));
            }
        });
    }

    private ProblemDraftGenerationJobEntity outcomeUpdate(ProblemDraftAgentCoordinator.AgentOutcome outcome) {
        ProblemDraftGenerationJobEntity update = new ProblemDraftGenerationJobEntity();
        if (outcome != null) {
            update.setAgentRunId(outcome.runId());
            update.setStateVersion(outcome.stateVersion());
            update.setReviewRisk(outcome.reviewRisk());
            update.setAgentMode(outcome.agentMode());
            update.setContentFingerprint(outcome.contentFingerprint());
            update.setVerificationFingerprint(outcome.verificationFingerprint());
        }
        return update;
    }

    private UpdateWrapper<ProblemDraftGenerationJobEntity> terminalWrapper(Long jobId) {
        return new UpdateWrapper<ProblemDraftGenerationJobEntity>()
                .set("lease_owner", null).set("lease_expires_at", null)
                .eq("id", jobId).in("status", List.of(STATUS_RUNNING, STATUS_QUEUED));
    }

    private void recoverExpiredRunningJobs() {
        LocalDateTime now = LocalDateTime.now();
        List<ProblemDraftGenerationJobEntity> expired = jobMapper.selectList(
                new QueryWrapper<ProblemDraftGenerationJobEntity>()
                        .eq("status", STATUS_RUNNING).le("lease_expires_at", now)
                        .orderByAsc("lease_expires_at").last("LIMIT " + WORKER_BATCH_SIZE));
        for (ProblemDraftGenerationJobEntity job : expired) {
            if (inFlightJobs.contains(job.getId())) {
                continue;
            }
            needsAttention(job.getId(), null, ErrorCode.INTERNAL_ERROR.code(), "agent.workerInterrupted",
                    "The Agent worker stopped before completion; the workspace state was preserved");
        }
    }

    /** The lease must outlive the run budget; an explicit override wins when configured. */
    private long leaseMinutes() {
        int configured = aiProperties.getProblemDraft().getJobLeaseMinutes();
        return configured > 0 ? configured : aiProperties.getProblemDraft().getMaxRunMinutes() + 5L;
    }

    private void applyListFilters(QueryWrapper<ProblemDraftGenerationJobEntity> query, boolean admin,
                                  Long creatorUserId, String status) {
        if (status != null && !status.isBlank()) {
            String normalized = status.trim().toUpperCase();
            if (!isKnownStatus(normalized)) {
                throw new DomainException(ErrorCode.BAD_REQUEST, "Unsupported generation job status");
            }
            query.eq("status", normalized);
        }
        if (admin) {
            if (creatorUserId != null) {
                query.eq("creator_user_id", creatorUserId);
            }
        } else {
            query.eq("creator_user_id", SecuritySupport.currentUserId());
        }
    }

    private ProblemDraftGenerationJobEntity findByTurn(String turnId) {
        return jobMapper.selectOne(new QueryWrapper<ProblemDraftGenerationJobEntity>()
                .eq("turn_id", turnId).last("LIMIT 1"));
    }

    private ProblemDraftGenerationJobEntity requireJob(Long id) {
        ProblemDraftGenerationJobEntity job = jobMapper.selectById(id);
        if (job == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "Problem draft generation job not found");
        }
        return job;
    }

    private ProblemDraftAgentTurnResponse turnResponse(ProblemDraftWorkspaceEntity workspace,
                                                        ProblemDraftGenerationJobEntity job,
                                                        boolean replay) {
        return new ProblemDraftAgentTurnResponse(workspace.getId(), job.getTurnId(), job.getId(),
                job.getAgentRunId(), workspace.getCurrentDraftId(), job.getStatus(), replay);
    }

    private void replaceAudit(ProblemDraftGenerationJobEntity job, String actionState, String status,
                              Map<String, Object> summary) {
        auditWriter.replaceProblemDraftGenerationJobLifecycle(job.getId(), auditAction(job, actionState),
                status, summary);
    }

    private String auditAction(ProblemDraftGenerationJobEntity job, String state) {
        String prefix = switch (jobType(job)) {
            case JOB_TYPE_REGENERATE -> "PROBLEM_DRAFT_REGENERATION_JOB";
            case JOB_TYPE_OPTIMIZE -> "PROBLEM_DRAFT_OPTIMIZATION_JOB";
            default -> "PROBLEM_DRAFT_GENERATION_JOB";
        };
        return prefix + "_" + state;
    }

    private Map<String, Object> auditSummary(ProblemDraftGenerationJobEntity job, String timestampKey,
                                             LocalDateTime timestamp, Long draftId, Integer errorCode,
                                             String errorKey) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("jobId", job.getId());
        summary.put("workspaceId", job.getWorkspaceId());
        summary.put("turnId", job.getTurnId());
        if (job.getAgentRunId() != null) summary.put("agentRunId", job.getAgentRunId());
        if (job.getSourceDraftId() != null) summary.put("sourceDraftId", job.getSourceDraftId());
        if (draftId != null) summary.put("draftId", draftId);
        if (timestampKey != null && timestamp != null) summary.put(timestampKey, toInstant(timestamp).toString());
        if (errorCode != null) summary.put("errorCode", errorCode);
        if (errorKey != null && !errorKey.isBlank()) summary.put("errorKey", errorKey);
        return summary;
    }

    private ProblemDraftGenerationJobResponse toResponse(ProblemDraftGenerationJobEntity job) {
        return new ProblemDraftGenerationJobResponse(
                job.getId(), job.getCreatorUserId(), jobType(job), job.getSourceDraftId(), job.getStatus(),
                job.getStage(), job.getTopicSnapshot(), valueOr(job.getProgressCurrent(), 0),
                valueOr(job.getProgressTotal(), PROGRESS_TOTAL), job.getProgressMessage(), job.getDraftId(),
                job.getErrorCode(), job.getErrorKey(), job.getErrorMessage(), toInstant(job.getStartedAt()),
                toInstant(job.getCompletedAt()), toInstant(job.getCreatedAt()), toInstant(job.getUpdatedAt()),
                job.getWorkspaceId(), job.getAgentRunId(), valueOr(job.getStateVersion(), 0L),
                job.getRequestedCfRating(), riskService.fromCode(job.getReviewRisk()), job.getAgentMode(),
                job.getContentFingerprint(), job.getVerificationFingerprint());
    }

    private String toJson(Object request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            throw new DomainException(ErrorCode.BAD_REQUEST, "Problem draft request could not be serialized");
        }
    }

    private String jobType(ProblemDraftGenerationJobEntity job) {
        return job == null || job.getJobType() == null || job.getJobType().isBlank()
                ? JOB_TYPE_GENERATE : job.getJobType().trim().toUpperCase();
    }

    private boolean isKnownStatus(String status) {
        return List.of(STATUS_QUEUED, STATUS_RUNNING, STATUS_SUCCEEDED, STATUS_NEEDS_ATTENTION,
                STATUS_FAILED, STATUS_CANCELLED).contains(status);
    }

    private String errorKey(ErrorCode errorCode) {
        return switch (errorCode) {
            case BAD_REQUEST, VALIDATION_FAILED, INVALID_PAYLOAD, MISSING_PARAMETER, TYPE_MISMATCH -> "request.invalid";
            case UNAUTHORIZED -> "auth.unauthorized";
            case FORBIDDEN -> "auth.forbidden";
            case NOT_FOUND -> "resource.notFound";
            case CONFLICT -> "resource.conflict";
            case TOO_MANY_REQUESTS -> "ai.busy";
            case SERVICE_UNAVAILABLE -> "service.unavailable";
            default -> "system.internal";
        };
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "Problem draft Agent turn failed; the working state was preserved";
        }
        return message.replaceAll("(?i)(api[-_ ]?key|authorization|token|password)=?\\S+", "$1=***");
    }

    private Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZONE).toInstant();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private int valueOr(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private long valueOr(Long value, long fallback) {
        return value == null ? fallback : value;
    }
}
