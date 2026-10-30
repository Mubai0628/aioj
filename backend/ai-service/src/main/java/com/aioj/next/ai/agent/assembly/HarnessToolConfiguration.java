package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.tool.api.BulkheadLimits;
import com.aioj.next.agent.harness.tool.api.ExecutionClass;
import com.aioj.next.agent.harness.tool.api.ToolCatalog;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.agent.harness.tool.api.ToolRejection;
import com.aioj.next.agent.harness.tool.api.ToolRuntime;
import com.aioj.next.agent.harness.tool.api.ToolRuntimes;
import com.aioj.next.ai.agent.context.HarnessContextConfiguration;
import com.aioj.next.ai.agent.tool.AgentTool;
import com.aioj.next.ai.agent.tool.ToolAuditService;
import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.persistence.mapper.ProblemDraftWorkspaceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Harness tool pipeline over ai-service's tools (plan §3.3, T1.5): the catalog, the
 * workspace authorizer, the ai_tool_calls audit and one bulkhead per execution class.
 * A tool whose descriptor breaks the rules is left out and logged instead of stopping
 * the service (A2.4).
 */
@Configuration(proxyBeanMethods = false)
public class HarnessToolConfiguration {
    private static final Logger log = LoggerFactory.getLogger(HarnessToolConfiguration.class);

    /** Server-owned draft ids, on top of the Harness's own identity and permission fields. */
    public static final Set<String> DRAFT_FORBIDDEN_INPUT_FIELDS = Set.of("draftid", "generationjobid");

    @Bean(destroyMethod = "close")
    public ToolRuntime harnessToolRuntime(List<AgentTool> tools, ProblemDraftWorkspaceMapper workspaceMapper,
                                          ToolAuditService auditService, AiProperties aiProperties,
                                          ObjectMapper objectMapper) {
        return create(tools, workspaceMapper, auditService, aiProperties.getCapacity(), objectMapper);
    }

    /** The same pipeline outside Spring, for tests that run the loop end to end. */
    public static ToolRuntime create(List<AgentTool> tools, ProblemDraftWorkspaceMapper workspaceMapper,
                                     ToolAuditService auditService, AiProperties.Capacity capacity,
                                     ObjectMapper objectMapper) {
        ToolCatalog catalog = catalog(tools);
        for (ToolRejection rejection : catalog.rejections()) {
            log.warn("Agent tool left out of the catalog: tool={} class={} reasons={}", rejection.toolName(),
                    rejection.toolClass(), rejection.reasons());
        }
        Map<String, ToolDescriptor> descriptors = new LinkedHashMap<>();
        tools.forEach(tool -> {
            if (tool.descriptor() != null && tool.descriptor().name() != null) {
                descriptors.putIfAbsent(tool.descriptor().name(), tool.descriptor());
            }
        });
        // Tool threads log, and call agent-executor and problem-service, under the turn's trace id.
        return ToolRuntimes.create(catalog, List.of(new WorkspaceOwnershipAuthorizer(workspaceMapper)),
                new ToolCallAuditSink(auditService, descriptors), bulkheadLimits(capacity), objectMapper,
                HarnessContextConfiguration.carryLoggingContext());
    }

    /** The tools as the Harness registers them; a tool that breaks the rules is in its rejections. */
    public static ToolCatalog catalog(List<AgentTool> tools) {
        return ToolCatalog.of(tools.stream().map(BridgedTool::new).toList(), DRAFT_FORBIDDEN_INPUT_FIELDS);
    }

    /**
     * Each execution class gets what the single tool pool effectively offered: its core
     * threads and its queue. A full sandbox class can no longer starve the reads.
     */
    static BulkheadLimits bulkheadLimits(AiProperties.Capacity capacity) {
        Map<ExecutionClass, Integer> threads = new EnumMap<>(ExecutionClass.class);
        Map<ExecutionClass, Integer> queueSlots = new EnumMap<>(ExecutionClass.class);
        for (ExecutionClass executionClass : ExecutionClass.values()) {
            threads.put(executionClass, capacity.getToolCorePoolSize());
            queueSlots.put(executionClass, capacity.getToolQueueCapacity());
        }
        return new BulkheadLimits(threads, queueSlots);
    }
}
