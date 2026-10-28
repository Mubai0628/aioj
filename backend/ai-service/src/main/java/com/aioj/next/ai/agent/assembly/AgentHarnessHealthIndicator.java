package com.aioj.next.ai.agent.assembly;

import com.aioj.next.agent.harness.tool.api.ToolCatalog;
import com.aioj.next.agent.harness.tool.api.ToolRejection;
import com.aioj.next.ai.agent.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /actuator/health/agentHarness} (plan T1.6, A2.4): which tools the Harness catalog left
 * out and which scenarios miss a required tool. A scenario that misses one makes this
 * component {@code DEGRADED}, which the status order ranks below UP and maps to HTTP 200: the
 * service keeps serving, the overall health and the liveness and readiness groups stay as they
 * are (plan §3.5: liveness never depends on the Harness).
 */
@Component
public class AgentHarnessHealthIndicator implements HealthIndicator {
    public static final Status DEGRADED = new Status("DEGRADED", "a scenario lacks a required tool");

    private static final Logger log = LoggerFactory.getLogger(AgentHarnessHealthIndicator.class);

    private final Health health;

    @Autowired
    public AgentHarnessHealthIndicator(List<AgentTool> tools) {
        this(HarnessToolConfiguration.catalog(tools), AgentScenarios.REQUIRED_TOOLS);
    }

    AgentHarnessHealthIndicator(ToolCatalog catalog, Map<String, List<String>> requiredTools) {
        Map<String, Object> scenarios = new LinkedHashMap<>();
        boolean degraded = false;
        for (Map.Entry<String, List<String>> scenario : requiredTools.entrySet()) {
            List<String> missing = catalog.missing(scenario.getValue());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("required", scenario.getValue().size());
            detail.put("missing", missing);
            scenarios.put(scenario.getKey(), detail);
            if (!missing.isEmpty()) {
                degraded = true;
                log.warn("Agent scenario degraded: scenario={} missing required tools={}", scenario.getKey(), missing);
            }
        }
        List<Map<String, Object>> rejected = catalog.rejections().stream().map(AgentHarnessHealthIndicator::rejection).toList();
        this.health = Health.status(degraded ? DEGRADED : Status.UP)
                .withDetail("scenarios", scenarios)
                .withDetail("rejectedTools", rejected)
                .build();
    }

    @Override
    public Health health() {
        return health;
    }

    private static Map<String, Object> rejection(ToolRejection rejection) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tool", rejection.toolName());
        item.put("reasons", rejection.reasons());
        return item;
    }
}
