package com.aioj.next.common.security;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan S8 (T2.7): both sandboxes stay bounded in both compose files, and what judge-worker and
 * agent-executor ask of them fits inside: a run's memory below the container limit with room for
 * go-judge, and a cached executable retired before go-judge's own file timeout drops it.
 */
class SandboxComposeHardeningTest {
    private static final Path DEPLOY = Path.of("..", "..", "deploy");
    private static final Path BACKEND = Path.of("..");
    private static final Pattern DEFAULT = Pattern.compile("\\$\\{[A-Za-z0-9_]+:-?([^}]*)}");
    private static final List<String> FILES = List.of("compose.yml", "compose.production.yml");

    @Test
    void bothSandboxesAreBoundedInBothComposeFiles() throws IOException {
        for (String file : FILES) {
            Map<String, Object> compose = load(DEPLOY.resolve(file));
            Map<String, Object> services = map(compose.get("services"));
            for (String name : List.of("sandbox", "agent-sandbox")) {
                Map<String, Object> sandbox = map(services.get(name));
                String where = file + " " + name;
                assertThat(sandbox.get("read_only")).as(where + " read_only").isEqualTo(true);
                assertThat(sandbox.get("pids_limit")).as(where + " pids_limit").isInstanceOf(Integer.class);
                assertThat(sandbox.get("mem_limit")).as(where + " mem_limit").isNotNull();
                assertThat(list(sandbox.get("tmpfs"))).as(where + " bounded /tmp")
                        .anySatisfy(entry -> assertThat(entry.toString()).startsWith("/tmp:").contains("size="));
                assertThat(list(sandbox.getOrDefault("volumes", List.of()))).as(where + " /tmp on a volume")
                        .noneSatisfy(volume -> assertThat(volume.toString()).endsWith(":/tmp"));
                assertThat(map(sandbox.get("environment")).keySet()).as(where + " environment")
                        .allSatisfy(key -> assertThat(key).startsWith("ES_"));
            }
            assertThat(map(services.get("agent-sandbox"))).as(file + " agent-sandbox ports").doesNotContainKey("ports");
            if (file.contains("production")) {
                assertThat(map(services.get("sandbox"))).as("production sandbox ports").doesNotContainKey("ports");
                Map<String, Object> networks = map(compose.get("networks"));
                assertThat(map(networks.get("judge-net")).get("internal")).isEqualTo(true);
                assertThat(map(networks.get("agent-sandbox-net")).get("internal")).isEqualTo(true);
            } else {
                assertThat(list(map(services.get("sandbox")).get("ports"))).as("local sandbox binds loopback only")
                        .allSatisfy(port -> assertThat(port.toString()).startsWith("127.0.0.1:"));
            }
        }
    }

    @Test
    void whatTheCallersAskForFitsInsideTheSandboxes() throws IOException {
        Map<String, Object> judge = map(map(load(BACKEND.resolve("judge-worker/src/main/resources/application.yml"))
                .get("aioj")).get("judge"));
        Map<String, Object> executor = map(map(load(BACKEND.resolve("agent-executor/src/main/resources/application.yml"))
                .get("aioj")).get("executor"));
        for (String file : FILES) {
            Map<String, Object> services = map(load(DEPLOY.resolve(file)).get("services"));
            Map<String, Object> executorEnv = map(map(services.get("agent-executor")).get("environment"));
            long judgeSandboxKb = megabytes(map(services.get("sandbox")).get("mem_limit")) * 1024;
            long agentSandboxKb = megabytes(map(services.get("agent-sandbox")).get("mem_limit")) * 1024;

            assertThat(number(judge.get("max-memory-limit-kb")))
                    .as(file + ": a judge run leaves 256 MiB of the sandbox to go-judge and its files")
                    .isLessThanOrEqualTo(judgeSandboxKb - 262_144);
            long run = number(executorEnv.getOrDefault("AGENT_EXECUTOR_MAX_MEMORY_LIMIT_KB", executor.get("max-memory-limit-kb")));
            long compile = number(executorEnv.getOrDefault("AGENT_EXECUTOR_MAX_COMPILE_MEMORY_LIMIT_KB",
                    executor.get("max-compile-memory-limit-kb")));
            assertThat(Math.max(run, compile)).as(file + ": an executor run or compile leaves 128 MiB of agent-sandbox")
                    .isLessThanOrEqualTo(agentSandboxKb - 131_072);

            long ttl = number(executor.get("compile-cache-ttl-seconds"));
            long longestRun = number(executor.get("max-run-time-limit-millis")) / 1000;
            long fileTimeout = seconds(map(map(services.get("agent-sandbox")).get("environment")).get("ES_FILE_TIMEOUT"));
            assertThat(ttl + 60 + longestRun).as(file + ": cache life + sweep + a run < agent-sandbox file timeout")
                    .isLessThan(fileTimeout);
        }
    }

    private static Map<String, Object> load(Path file) throws IOException {
        return new Yaml().load(Files.readString(file));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    /** A number, or the default of a ${VAR:default} / ${VAR:-default} placeholder. */
    private static long number(Object value) {
        return Long.parseLong(placeholderDefault(value));
    }

    private static long megabytes(Object value) {
        String text = placeholderDefault(value).toLowerCase();
        long amount = Long.parseLong(text.replaceAll("[^0-9]", ""));
        return text.endsWith("g") ? amount * 1024 : amount;
    }

    private static long seconds(Object value) {
        String text = placeholderDefault(value).toLowerCase();
        long amount = Long.parseLong(text.replaceAll("[^0-9]", ""));
        return text.endsWith("h") ? amount * 3600 : text.endsWith("m") ? amount * 60 : amount;
    }

    private static String placeholderDefault(Object value) {
        String text = String.valueOf(value).trim();
        Matcher placeholder = DEFAULT.matcher(text);
        return placeholder.matches() ? placeholder.group(1).trim() : text;
    }
}
