package com.aioj.next.executor.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan §3.6 rule 3: agent-executor's configuration may read only its own
 * inbound token, the agent sandbox's settings, its limits and its log level;
 * never database, queue, JWT or model settings or the shared internal token.
 */
class ConfigurationWhitelistTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)");
    private static final Pattern ALLOWED = Pattern.compile("AGENT_EXECUTOR_[A-Z0-9_]+|AGENT_SANDBOX_[A-Z0-9_]+|user\\.home");

    @Test
    void applicationYamlReadsOnlyWhitelistedVariables() throws IOException {
        String yaml;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(input).isNotNull();
            yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Set<String> names = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(yaml);
        while (matcher.find()) {
            names.add(matcher.group(1).trim());
        }

        assertThat(names).isNotEmpty().allSatisfy(name -> assertThat(name).matches(ALLOWED));
        assertThat(names).contains("AGENT_EXECUTOR_INTERNAL_TOKEN", "AGENT_EXECUTOR_INTERNAL_TOKEN_NEXT", "AGENT_SANDBOX_ENDPOINT", "AGENT_SANDBOX_TOKEN")
                .doesNotContain("AIOJ_INTERNAL_API_TOKEN", "SANDBOX_TOKEN", "SANDBOX_ENDPOINT",
                        "AIOJ_INTERNAL_TOKEN_AI_TO_PROBLEM", "AIOJ_INTERNAL_TOKEN_JUDGE_TO_PROBLEM",
                        "AIOJ_INTERNAL_TOKEN_PROBLEM_TO_AI", "AIOJ_INTERNAL_TOKEN_PROBLEM_TO_JUDGE",
                        "AIOJ_INTERNAL_TOKEN_AI_TO_PROBLEM_NEXT", "AIOJ_INTERNAL_TOKEN_JUDGE_TO_PROBLEM_NEXT",
                        "AIOJ_INTERNAL_TOKEN_PROBLEM_TO_AI_NEXT", "AIOJ_INTERNAL_TOKEN_PROBLEM_TO_JUDGE_NEXT");
        assertThat(yaml).doesNotContainIgnoringCase("datasource").doesNotContainIgnoringCase("rabbitmq")
                .doesNotContainIgnoringCase("jwt");
    }
}
