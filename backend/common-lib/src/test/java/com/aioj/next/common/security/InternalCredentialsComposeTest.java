package com.aioj.next.common.security;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

class InternalCredentialsComposeTest {
    private static final String AI_PROBLEM = "AIOJ_INTERNAL_TOKEN_AI_TO_PROBLEM";
    private static final String JUDGE_PROBLEM = "AIOJ_INTERNAL_TOKEN_JUDGE_TO_PROBLEM";
    private static final String PROBLEM_AI = "AIOJ_INTERNAL_TOKEN_PROBLEM_TO_AI";
    private static final String PROBLEM_JUDGE = "AIOJ_INTERNAL_TOKEN_PROBLEM_TO_JUDGE";
    private static final String EXECUTOR = "AGENT_EXECUTOR_INTERNAL_TOKEN";

    @Test
    void localAndProductionComposeGiveEveryServiceExactlyItsDirectionalCredentials() throws Exception {
        for (String file : new String[]{"compose.yml", "compose.production.yml"}) {
            String yaml = Files.readString(Path.of("..", "..", "deploy", file));
            var matrix = credentials(yaml);
            assertThat(yaml).doesNotContain("AIOJ_INTERNAL_API_TOKEN");
            assertThat(matrix.get("problem-service")).containsExactlyInAnyOrder(
                    AI_PROBLEM, JUDGE_PROBLEM, PROBLEM_AI, PROBLEM_JUDGE, AI_PROBLEM + "_NEXT", JUDGE_PROBLEM + "_NEXT");
            assertThat(matrix.get("ai-service")).containsExactlyInAnyOrder(
                    AI_PROBLEM, PROBLEM_AI, EXECUTOR, PROBLEM_AI + "_NEXT");
            assertThat(matrix.get("judge-worker")).containsExactlyInAnyOrder(
                    JUDGE_PROBLEM, PROBLEM_JUDGE, PROBLEM_JUDGE + "_NEXT", "SANDBOX_TOKEN");
            assertThat(matrix.get("agent-executor")).containsExactlyInAnyOrder(
                    EXECUTOR, EXECUTOR + "_NEXT", "AGENT_SANDBOX_TOKEN");
            assertThat(matrix.getOrDefault("gateway-service", Set.of())).isEmpty();
            assertThat(matrix.getOrDefault("auth-service", Set.of())).isEmpty();
            assertThat(matrix.get("sandbox")).containsExactly("ES_AUTH_TOKEN");
            assertThat(matrix.get("agent-sandbox")).containsExactly("ES_AUTH_TOKEN");
            assertThat(yaml).contains("ES_AUTH_TOKEN: ${SANDBOX_TOKEN:")
                    .contains("ES_AUTH_TOKEN: ${AGENT_SANDBOX_TOKEN:");
            if (file.contains("production")) {
                for (String required : new String[]{AI_PROBLEM, JUDGE_PROBLEM, PROBLEM_AI, PROBLEM_JUDGE, EXECUTOR}) {
                    assertThat(yaml).contains("${" + required + ":?");
                }
            }
        }
    }

    private static Map<String, Set<String>> credentials(String yaml) {
        var services = Pattern.compile("^  ([a-z][a-z0-9-]+):$");
        var variable = Pattern.compile("^      ([A-Z][A-Z0-9_]*TOKEN[A-Z0-9_]*):.*$");
        Map<String, Set<String>> matrix = new HashMap<>();
        String service = null;
        for (String line : yaml.split("\\R")) {
            var match = services.matcher(line);
            if (match.matches()) {
                service = match.group(1);
                matrix.put(service, new HashSet<>());
            } else {
                var token = variable.matcher(line);
                if (service != null && token.matches()) { matrix.get(service).add(token.group(1)); }
            }
        }
        return matrix;
    }
}
