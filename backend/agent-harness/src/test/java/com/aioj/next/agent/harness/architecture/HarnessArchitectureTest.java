package com.aioj.next.agent.harness.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Architecture guards for agent-harness (plan §3.1, §3.3, A2.3, D2). The
 * fixture test proves each rule reports a violation; a rule that cannot fail
 * guards nothing.
 */
class HarnessArchitectureTest {
    static final String BASE = "com.aioj.next.agent.harness";
    private static final String FIXTURE = BASE + ".architecture.fixture";

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE);

    @Test
    void theHarnessDependsOnTheJdkAndJacksonOnly() {
        HarnessArchitectureRules.onlyJdkAndJackson(BASE).check(MAIN);
    }

    @Test
    void internalPackagesAreUsedOnlyInsideTheirSubpackage() {
        HarnessArchitectureRules.internalStaysInsideItsSubpackage(BASE).check(MAIN);
    }

    @Test
    void dependenciesPointFromRuntimeToToolsToContext() {
        HarnessArchitectureRules.layersPointOneWay(BASE).check(MAIN);
        HarnessArchitectureRules.contextDoesNotKnowTools(BASE).check(MAIN);
    }

    @Test
    void submodulesMeetThroughTheirApiPackages() {
        HarnessArchitectureRules.everyClassBelongsToASubmodule(BASE).check(MAIN);
        HarnessArchitectureRules.submodulesMeetThroughTheirApi(BASE).check(MAIN);
    }

    @Test
    void theHarnessRunsNoCodeInProcess() {
        HarnessArchitectureRules.runsNoCodeInProcess(BASE).check(MAIN);
    }

    @Test
    void theSourcesUseNoDomainWords() {
        Path sources = Path.of("src/main/java");
        assertThat(Files.isDirectory(sources)).as("run from the module directory").isTrue();

        assertThat(HarnessArchitectureRules.domainWordHits(sources)).isEmpty();
    }

    @Test
    void theRulesCatchViolations() {
        JavaClasses fixture = new ClassFileImporter().importPackages(FIXTURE);

        assertThatThrownBy(() -> HarnessArchitectureRules.onlyJdkAndJackson(FIXTURE).check(fixture))
                .hasMessageContaining("FixtureRuntimeSide")
                .hasMessageContaining("org.assertj");
        assertThatThrownBy(() -> HarnessArchitectureRules.internalStaysInsideItsSubpackage(FIXTURE).check(fixture))
                .hasMessageContaining("FixtureRuntimeSide")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("FixtureToolSide"));
        assertThatThrownBy(() -> HarnessArchitectureRules.layersPointOneWay(FIXTURE).check(fixture))
                .hasMessageContaining("FixtureToolSide");
        // The fixture's runtime class lives outside any api/internal package and reaches into
        // tool.internal; the tool class reaches runtime, which has no api package at all.
        assertThatThrownBy(() -> HarnessArchitectureRules.everyClassBelongsToASubmodule(FIXTURE).check(fixture))
                .hasMessageContaining("FixtureRuntimeSide")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("FixtureInternalHelper"));
        assertThatThrownBy(() -> HarnessArchitectureRules.submodulesMeetThroughTheirApi(FIXTURE).check(fixture))
                .hasMessageContaining("FixtureRuntimeSide.crossesIntoInternal")
                .hasMessageContaining("FixtureToolSide.reachesUp");
        assertThatThrownBy(() -> HarnessArchitectureRules.runsNoCodeInProcess(FIXTURE).check(fixture))
                .hasMessageContaining("FixtureCodeRunner.withBuilder")
                .hasMessageContaining("FixtureCodeRunner.withRuntime")
                .hasMessageContaining("FixtureCodeRunner.withScriptEngine")
                .hasMessageContaining("FixtureCodeRunner.withDefineClass")
                .hasMessageContaining("FixtureCodeRunner> is assignable to java.lang.ClassLoader");
        assertThat(HarnessArchitectureRules.DOMAIN_WORDS.matcher("a Contest rule").find()).isTrue();
        assertThat(HarnessArchitectureRules.DOMAIN_WORDS.matcher("contested resources").find()).isTrue();
        assertThat(HarnessArchitectureRules.DOMAIN_WORDS.matcher("a sandbox run").find()).isFalse();
    }
}
