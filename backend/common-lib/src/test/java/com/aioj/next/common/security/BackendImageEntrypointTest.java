package com.aioj.next.common.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A container's stop signal goes to PID 1. Under {@code sh -c "java ..."} that was the shell, which
 * ignores SIGTERM, so Docker waited out its timeout and killed Java: no graceful shutdown and no
 * {@code @PreDestroy} in any of the six backend images (the executor's exit 137, appendix E.7). The
 * shell stays to expand JAVA_OPTS and then hands PID 1 to Java with exec.
 */
class BackendImageEntrypointTest {
    private static final Pattern SHELL_FORM = Pattern.compile("ENTRYPOINT\\s*\\[\\s*\"sh\"\\s*,\\s*\"-c\"\\s*,\\s*\"([^\"]*)\"\\s*]");

    @Test
    void javaTakesOverPidOneSoTheStopSignalReachesIt() throws Exception {
        List<String> starts = Files.readAllLines(Path.of("..", "..", "deploy", "docker", "backend.Dockerfile")).stream()
                .map(String::trim)
                .filter(line -> line.startsWith("ENTRYPOINT") || line.startsWith("CMD"))
                .toList();

        assertThat(starts).as("one way to start the service").hasSize(1);
        Matcher entrypoint = SHELL_FORM.matcher(starts.get(0));
        assertThat(entrypoint.matches()).as(starts.get(0)).isTrue();
        assertThat(entrypoint.group(1)).as("the shell execs Java with the image's JAVA_OPTS")
                .startsWith("exec java ")
                .contains("$JAVA_OPTS")
                .doesNotContain(";", "&&", "|");
    }
}
