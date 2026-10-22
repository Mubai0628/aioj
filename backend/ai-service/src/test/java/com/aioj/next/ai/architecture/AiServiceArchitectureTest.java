package com.aioj.next.ai.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Architecture guards for ai-service (plan T0.4; rules from §3.1, §3.2.1 and §6.2).
 *
 * <p>The model call package {@value #MODEL_PACKAGE} does not exist until T1.2b.
 * Its rules already hold, so they apply from its first commit; the fixture test
 * proves they catch violations.
 *
 * <p>Today's code is frozen in explicit allowlists. A class that is not listed
 * fails the build, and so does a listed class that no longer needs the entry:
 * the lists only shrink while T1.1–T1.3 move callers onto ModelClient. Never
 * add an entry to make a build pass; route the new code through the model
 * package instead.
 */
class AiServiceArchitectureTest {
    static final String MODEL_PACKAGE = "com.aioj.next.ai.model";

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.aioj.next.ai");

    /** Plan §1.2: the three legacy model call paths, frozen until T1.3 deletes them (§3.2.1 rule 3). */
    private static final Map<String, Set<String>> LEGACY_MODEL_PATH_CALLERS = Map.of(
            "com.aioj.next.ai.agent.model.ModelGateway", Set.of(
                    "com.aioj.next.ai.agent.digest.TurnDigestCuratorHandler",
                    "com.aioj.next.ai.agent.runtime.AgentRuntime",
                    "com.aioj.next.ai.agent.telemetry.ContestAssistanceIntentJudge",
                    "com.aioj.next.ai.agent.understanding.TurnUnderstandingService"),
            "com.aioj.next.ai.domain.AiProvider", Set.of(
                    "com.aioj.next.ai.controller.AiController",
                    "com.aioj.next.ai.domain.AiChatTurnService",
                    "com.aioj.next.ai.domain.AiMemoryService",
                    "com.aioj.next.ai.domain.AiRetrievalService",
                    "com.aioj.next.ai.domain.ContestPostmortemAnalysisService",
                    "com.aioj.next.ai.domain.ContestTurnGuard",
                    "com.aioj.next.ai.domain.PlagiarismAnalysisService",
                    "com.aioj.next.ai.domain.ProblemDraftStore",
                    "com.aioj.next.ai.domain.StudentPostmortemAnalysisService",
                    "com.aioj.next.ai.domain.embedding.AiEmbeddingService"),
            "com.aioj.next.ai.domain.OpenAiCompatibleProvider", Set.of(
                    "com.aioj.next.ai.agent.digest.TurnDigestEmbedHandler",
                    "com.aioj.next.ai.agent.retrieval.builtin.ContextSearchDigestsTool"),
            "com.aioj.next.ai.domain.AiModelCompletionClient", Set.of(
                    "com.aioj.next.ai.domain.AccountImportParseService",
                    "com.aioj.next.ai.domain.AiModelConfigService",
                    "com.aioj.next.ai.domain.AiStructuredSubmissionAnalysisService",
                    "com.aioj.next.ai.domain.ContestTurnGuard",
                    "com.aioj.next.ai.domain.memory.AiMemoryMergeService"),
            "com.aioj.next.ai.domain.AiModelCatalogClient", Set.of(
                    "com.aioj.next.ai.domain.AiModelConfigService"));

    /**
     * Plan §3.2.1 rule 1: Provider HTTP calls happen only in the model call package.
     * A Provider call needs its API key, so key reads mark where calls can happen:
     * today the three legacy clients, the agent adapters, the config resolver and
     * the admin connectivity test. After T1.3 only the model package and the
     * ModelConfigSource implementation remain.
     */
    private static final Set<String> PROVIDER_API_KEY_READERS = Set.of(
            "com.aioj.next.ai.agent.model.AbstractOpenAiToolCallAdapter",
            "com.aioj.next.ai.domain.AiModelCatalogClient",
            "com.aioj.next.ai.domain.AiModelCompletionClient",
            "com.aioj.next.ai.domain.AiModelConfigService",
            "com.aioj.next.ai.domain.EnvironmentAiModelConfigResolver",
            "com.aioj.next.ai.domain.OpenAiCompatibleProvider");

    /**
     * Plan §1.2: agent.model, the future core of the model call package, already
     * reaches into the harness and business code; T1.2b removes these. Until then
     * it gains no new ones.
     */
    private static final Set<String> AGENT_MODEL_OUTSIDE_DEPENDENCIES = Set.of(
            "com.aioj.next.ai.agent.tool.ToolDescriptor",
            "com.aioj.next.ai.agent.tool.ToolNameCodec",
            "com.aioj.next.ai.config.AiProperties",
            "com.aioj.next.ai.domain.AiModelConfigService",
            "com.aioj.next.ai.domain.AiModelEffectiveConfig",
            "com.aioj.next.ai.domain.AiModelScope");

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
    void legacyModelPathsTakeNoNewCallers() {
        // The legacy classes depend on each other; only callers from elsewhere count.
        LEGACY_MODEL_PATH_CALLERS.forEach((legacy, allowed) -> assertExactly("callers of " + legacy,
                AiServiceArchitectureRules.dependentsOf(MAIN, legacy, LEGACY_MODEL_PATH_CALLERS.keySet()), allowed));
    }

    @Test
    void providerApiKeysAreReadOnlyWhereProviderCallsHappenToday() {
        assertExactly("classes reading a Provider API key",
                AiServiceArchitectureRules.callersOf(MAIN, call -> call.getTargetOwner().getPackageName().startsWith("com.aioj.next.ai")
                        && (call.getName().equals("apiKey") || call.getName().equals("getApiKey"))),
                PROVIDER_API_KEY_READERS);
    }

    @Test
    void agentModelGainsNoNewOutsideDependencies() {
        assertExactly("ai-service classes outside agent.model that agent.model depends on",
                AiServiceArchitectureRules.externalTargetsOf(MAIN, "com.aioj.next.ai.agent.model", "com.aioj.next.ai"),
                AGENT_MODEL_OUTSIDE_DEPENDENCIES);
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
