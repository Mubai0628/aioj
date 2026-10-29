package com.aioj.next.ai.health;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.boot.actuate.endpoint.ApiVersion;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointWebExtension;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;

class HealthGroupsConfigurationTest {
    private static final List<String> AGENT = List.of("agentHarness", "modelProviders", "agentExecutor", "agentChannel", "problemService");

    static Stream<Arguments> services() {
        return Stream.of(Arguments.of("gateway-service", Set.of()), Arguments.of("auth-service", Set.of("db")),
                Arguments.of("problem-service", Set.of("db", "rabbit")), Arguments.of("judge-worker", Set.of("db", "rabbit")),
                Arguments.of("ai-service", Set.of("db")), Arguments.of("agent-executor", Set.of()));
    }

    @ParameterizedTest
    @MethodSource("services")
    void everyServicesActualYamlKeepsLivenessIndependentAndOnlyRequiredDependenciesGateReadiness(
            String service, Set<String> required) {
        var fault = new AtomicReference<>("");
        runner(service, fault).run(context -> {
            assertThat(context).hasNotFailed();
            var web = new HealthEndpointWebExtension(context.getBean(HealthContributorRegistry.class), null,
                    context.getBean(HealthEndpointGroups.class), Duration.ofSeconds(2));
            for (String dependency : List.of("db", "redis", "rabbit", "readinessState")) {
                fault.set(dependency);
                var live = web.health(ApiVersion.V3, WebServerNamespace.SERVER, SecurityContext.NONE, "liveness");
                var ready = web.health(ApiVersion.V3, WebServerNamespace.SERVER, SecurityContext.NONE, "readiness");
                boolean hard = required.contains(dependency) || dependency.equals("readinessState");
                assertThat(live.getStatus()).as(service + ": " + dependency).isEqualTo(200);
                assertThat(live.getBody().getStatus()).isEqualTo(Status.UP);
                assertThat(ready.getStatus()).as(service + ": " + dependency).isEqualTo(hard ? 503 : 200);
                assertThat(ready.getBody().getStatus()).isEqualTo(hard ? Status.DOWN : Status.UP);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"agentHarness", "modelProviders", "agentExecutor", "agentChannel", "problemService"})
    void eachDegradedAgentItemIsVisibleAs200WithoutChangingOverallOrReadiness(String item) {
        var fault = new AtomicReference<>(item);
        runner("ai-service", fault).run(context -> {
            assertThat(context).hasNotFailed();
            var web = new HealthEndpointWebExtension(context.getBean(HealthContributorRegistry.class), null,
                    context.getBean(HealthEndpointGroups.class), Duration.ofSeconds(2));
            var agent = web.health(ApiVersion.V3, WebServerNamespace.SERVER, SecurityContext.NONE, "agent");
            assertThat(agent.getStatus()).isEqualTo(200);
            assertThat(agent.getBody().getStatus().getCode()).isEqualTo("DEGRADED");
            for (String[] path : List.of(new String[0], new String[]{"liveness"}, new String[]{"readiness"})) {
                var health = web.health(ApiVersion.V3, WebServerNamespace.SERVER, SecurityContext.NONE, path);
                assertThat(health.getStatus()).isEqualTo(200);
                assertThat(health.getBody().getStatus()).isEqualTo(Status.UP);
            }
        });
    }

    ApplicationContextRunner runner(String service, AtomicReference<String> fault) {
        var runner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                HealthContributorRegistryAutoConfiguration.class, HealthEndpointAutoConfiguration.class))
                .withInitializer(context -> {
                    try {
                        new YamlPropertySourceLoader().load(service, new FileSystemResource(
                                backendRoot().resolve(service + "/src/main/resources/application.yml")))
                                .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                    } catch (IOException ex) { throw new UncheckedIOException(ex); }
                });
        for (String name : List.of("livenessState", "readinessState", "db", "redis", "rabbit",
                "agentHarness", "modelProviders", "agentExecutor", "agentChannel", "problemService")) {
            runner = runner.withBean(name, HealthIndicator.class, () -> () -> Health.status(
                    name.equals(fault.get()) ? AGENT.contains(name) ? "DEGRADED" : "DOWN" : "UP").build());
        }
        return runner;
    }

    private Path backendRoot() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("backend/pom.xml"))) { return path.resolve("backend"); }
        }
        throw new IllegalStateException("Repository backend root not found");
    }
}
