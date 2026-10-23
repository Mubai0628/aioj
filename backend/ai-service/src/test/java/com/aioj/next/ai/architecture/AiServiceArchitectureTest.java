package com.aioj.next.ai.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Architecture guards for ai-service (plan T0.4; rules from §3.1, §3.2.1 and §6.2).
 *
 * <p>The model call package {@value #MODEL_PACKAGE} arrived in T1.2b; the fixture
 * test proves its rules catch violations.
 *
 * <p>T1.3 moved every caller onto ModelClient and deleted the legacy paths; the
 * key-reader allowlist is exact. A class that is not listed fails the build, and
 * so does a listed class that no longer needs the entry. Never add an entry to
 * make a build pass; route the new code through the model package instead.
 */
class AiServiceArchitectureTest {
    static final String MODEL_PACKAGE = "com.aioj.next.ai.model";

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.aioj.next.ai");

    /**
     * Plan §1.2: the legacy model call paths that T1.3 replaced with the model call
     * package. They must not come back, under these names or as a second HTTP path.
     */
    private static final Set<String> RETIRED_MODEL_PATHS = Set.of(
            "com.aioj.next.ai.agent.model.ModelGateway",
            "com.aioj.next.ai.domain.AiProvider",
            "com.aioj.next.ai.domain.OpenAiCompatibleProvider",
            "com.aioj.next.ai.domain.AiModelCompletionClient",
            "com.aioj.next.ai.domain.AiModelCatalogClient");

    /** T1.5: the agent loop and the tool pipeline are the Harness's; ai-service keeps none of its own. */
    private static final Set<String> RETIRED_AGENT_PATHS = Set.of(
            "com.aioj.next.ai.agent.tool.ToolBroker",
            "com.aioj.next.ai.agent.tool.ToolRegistry",
            "com.aioj.next.ai.agent.tool.ToolAuthorizationService",
            "com.aioj.next.ai.agent.tool.ToolResultSanitizer",
            "com.aioj.next.ai.agent.tool.ToolInputSchemaValidator",
            "com.aioj.next.ai.agent.tool.ToolDescriptor",
            "com.aioj.next.ai.agent.tool.ToolRiskLevel",
            "com.aioj.next.ai.agent.tool.ToolAuditLevel");

    /**
     * Plan §3.2.1 rule 1: Provider HTTP calls happen only in the model call package.
     * A Provider call needs its API key, so key reads mark where calls can happen:
     * the model package's client and its ModelConfigSource implementation, the
     * config resolution that finds the keys, and the admin model catalog, which
     * builds the endpoint it asks the model package to list.
     */
    private static final Set<String> PROVIDER_API_KEY_READERS = Set.of(
            "com.aioj.next.ai.model.DefaultModelClient",
            "com.aioj.next.ai.domain.AiModelConfigEndpointSource",
            "com.aioj.next.ai.domain.AiModelConfigService",
            "com.aioj.next.ai.domain.EnvironmentAiModelConfigResolver",
            "com.aioj.next.ai.domain.ModelCatalogService");

    @Test
    void theModelPackageDependsOnNoPersistenceHarnessOrOtherAiServiceCode() {
        AiServiceArchitectureRules.modelPackageIsSelfContained(MODEL_PACKAGE).check(MAIN);
    }

    @Test
    void codeOutsideTheModelPackageUsesOnlyItsApi() {
        AiServiceArchitectureRules.onlyTheModelApiIsUsedFromOutside(MODEL_PACKAGE).check(MAIN);
    }

    @Test
    void internalPackagesAreUsedOnlyInsideTheirOwner() {
        AiServiceArchitectureRules.internalPackagesStayInsideTheirOwner().check(MAIN);
    }

    @Test
    void aiServiceStartsNoProcesses() {
        AiServiceArchitectureRules.noProcessExecution().check(MAIN);
    }

    @Test
    void retiredModelPathsAreGone() {
        Set<String> present = new TreeSet<>();
        for (JavaClass type : MAIN) {
            if (RETIRED_MODEL_PATHS.contains(type.getName())) {
                present.add(type.getName());
            }
        }
        assertThat(present).as("legacy model call paths (plan §3.2.1 rule 3)").isEmpty();
    }

    @Test
    void theOldAgentLoopAndToolPipelineAreGone() {
        Set<String> present = new TreeSet<>();
        for (JavaClass type : MAIN) {
            if (RETIRED_AGENT_PATHS.contains(type.getName())) {
                present.add(type.getName());
            }
        }
        assertThat(present).as("ai-service's own loop and tool pipeline (T1.5)").isEmpty();
        // The Harness tool pipeline is reached only through its api: no ai-service class builds the loop's internals.
        assertThat(AiServiceArchitectureRules.callersOf(MAIN,
                call -> call.getTargetOwner().getPackageName().startsWith("com.aioj.next.agent.harness")
                        && call.getTargetOwner().getPackageName().contains(".internal")))
                .as("callers of Harness internals").isEmpty();
    }

    @Test
    void providerApiKeysAreReadOnlyWhereProviderCallsHappenToday() {
        assertExactly("classes reading a Provider API key",
                AiServiceArchitectureRules.callersOf(MAIN, call -> call.getTargetOwner().getPackageName().startsWith("com.aioj.next.ai")
                        && (call.getName().equals("apiKey") || call.getName().equals("getApiKey"))),
                PROVIDER_API_KEY_READERS);
    }

    @Test
    void theRulesCatchViolations() {
        String fixtureModel = "com.aioj.next.ai.architecture.fixture.model";
        JavaClasses fixture = new ClassFileImporter().importPackages("com.aioj.next.ai.architecture.fixture");

        assertThatThrownBy(() -> AiServiceArchitectureRules.modelPackageIsSelfContained(fixtureModel).check(fixture))
                .hasMessageContaining("FixtureLeakyAdapter")
                .hasMessageContaining("BaseMapper")
                .hasMessageContaining("AiModelScope");
        assertThatThrownBy(() -> AiServiceArchitectureRules.onlyTheModelApiIsUsedFromOutside(fixtureModel).check(fixture))
                .hasMessageContaining("FixtureCaller")
                .hasMessageContaining("FixtureLeakyAdapter")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("FixtureModelClient"));
        assertThatThrownBy(() -> AiServiceArchitectureRules.internalPackagesStayInsideTheirOwner().check(fixture))
                .hasMessageContaining("FixtureCaller")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("FixtureLeakyAdapter"));
        assertThatThrownBy(() -> AiServiceArchitectureRules.noProcessExecution().check(fixture))
                .hasMessageContaining("FixtureProcessStarter.withBuilder")
                .hasMessageContaining("FixtureProcessStarter.withRuntime");
    }

    private static void assertExactly(String what, Set<String> actual, Set<String> allowed) {
        Set<String> added = new TreeSet<>(actual);
        added.removeAll(allowed);
        Set<String> stale = new TreeSet<>(allowed);
        stale.removeAll(actual);
        if (!added.isEmpty() || !stale.isEmpty()) {
            fail("%s differ from the allowlist.%n"
                    + "  Not allowed, route the code through the model call package (plan §3.2.1): %s%n"
                    + "  No longer needed, delete these entries so the list keeps shrinking: %s", what, added, stale);
        }
    }
}
