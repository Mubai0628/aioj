package com.aioj.next.agent.harness.testing;

import com.aioj.next.agent.harness.context.api.TrustLevel;
import com.aioj.next.agent.harness.tool.api.AgentTool;
import com.aioj.next.agent.harness.tool.api.ExecutionClass;
import com.aioj.next.agent.harness.tool.api.FailurePolicy;
import com.aioj.next.agent.harness.tool.api.ToolAuditLevel;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.agent.harness.tool.api.ToolExecutionContext;
import com.aioj.next.agent.harness.tool.api.ToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/** Builds descriptors, tools and contexts for the Harness tests. */
public final class Tools {
    public static final String QUERY_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "properties":{"query":{"type":"string","minLength":1,"maxLength":50}},
             "required":["query"]}""";
    private static final ObjectMapper JSON = new ObjectMapper();

    private Tools() {
    }

    public static Descriptor descriptor(String name) {
        return new Descriptor(name);
    }

    public static ToolExecutionContext context(Set<String> scopes) {
        return new ToolExecutionContext("run-1", "u-7", "c-1", "t-1", "ps-1", scopes, Instant.parse("2026-10-23T08:00:00Z"),
                "trace-1", Map.of(), Map.of());
    }

    public static ToolExecutionContext context(String runId, Set<String> scopes) {
        return new ToolExecutionContext(runId, "u-7", "c-1", "t-1", "ps-1", scopes, Instant.parse("2026-10-23T08:00:00Z"),
                "trace-1", Map.of(), Map.of());
    }

    /** A tool with a call counter; {@code behavior} gets the context and the validated input. */
    public static final class Fake implements AgentTool {
        private final ToolDescriptor descriptor;
        private final BiFunction<ToolExecutionContext, JsonNode, ToolResult> behavior;
        private final AtomicInteger calls = new AtomicInteger();

        public Fake(ToolDescriptor descriptor, BiFunction<ToolExecutionContext, JsonNode, ToolResult> behavior) {
            this.descriptor = descriptor;
            this.behavior = behavior;
        }

        @Override
        public ToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode input) {
            calls.incrementAndGet();
            return behavior.apply(context, input);
        }

        public int calls() {
            return calls.get();
        }
    }

    public static final class Descriptor {
        private final String name;
        private String schema = QUERY_SCHEMA;
        private Set<String> scopes = Set.of("CHAT");
        private int maxResultTokens = 1_000;
        private Duration timeout = Duration.ofSeconds(5);
        private String category = "lookup";
        private ExecutionClass executionClass = ExecutionClass.READ_ONLY;
        private FailurePolicy failurePolicy = FailurePolicy.FAIL_OPEN;

        private Descriptor(String name) {
            this.name = name;
        }

        public Descriptor schema(String json) {
            this.schema = json;
            return this;
        }

        public Descriptor scopes(String... required) {
            this.scopes = Set.of(required);
            return this;
        }

        public Descriptor maxResultTokens(int tokens) {
            this.maxResultTokens = tokens;
            return this;
        }

        public Descriptor timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        public Descriptor category(String value) {
            this.category = value;
            return this;
        }

        public Descriptor executionClass(ExecutionClass value) {
            this.executionClass = value;
            return this;
        }

        public Descriptor failurePolicy(FailurePolicy value) {
            this.failurePolicy = value;
            return this;
        }

        public ToolDescriptor build() {
            return new ToolDescriptor(name, "1.0.0", "Test tool " + name + ".", JSON.readTree(schema), scopes,
                    maxResultTokens, timeout, category, executionClass, failurePolicy, ToolAuditLevel.FULL);
        }

        /** A tool that answers {"echo": input}. */
        public Fake echo() {
            return new Fake(build(), (context, input) -> ToolResult.success(Map.of("echo", input), List.of(),
                    "PUBLIC", TrustLevel.SERVER_AUTHORITATIVE));
        }

        public Fake behaving(BiFunction<ToolExecutionContext, JsonNode, ToolResult> behavior) {
            return new Fake(build(), behavior);
        }
    }
}
