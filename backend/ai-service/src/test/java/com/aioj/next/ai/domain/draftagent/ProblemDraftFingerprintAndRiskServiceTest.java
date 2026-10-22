package com.aioj.next.ai.domain.draftagent;

import com.aioj.next.contract.problem.TestCaseDto;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDraftFingerprintAndRiskServiceTest {

    private final ProblemDraftFingerprintService fingerprints =
            new ProblemDraftFingerprintService(JsonMapper.builderWithJackson2Defaults().build());
    private final ProblemDraftReviewRiskService risks = new ProblemDraftReviewRiskService();

    @Test
    void titleAndTagsChangeContentFingerprintButDoNotInvalidateExecutionAcceptance() {
        ProblemDraftWorkingState first = state("First title", List.of("dp"), "statement");
        ProblemDraftWorkingState metadataOnly = state("Renamed title", List.of("dp", "math"), "statement");

        assertThat(fingerprints.contentFingerprint(first, 1600))
                .isNotEqualTo(fingerprints.contentFingerprint(metadataOnly, 1600));
        assertThat(fingerprints.verificationFingerprint(first, 1600))
                .isEqualTo(fingerprints.verificationFingerprint(metadataOnly, 1600));
    }

    @Test
    void statementOrRequestedDifficultyRatingInvalidatesExecutionAcceptance() {
        ProblemDraftWorkingState first = state("Title", List.of("dp"), "statement");
        ProblemDraftWorkingState changed = state("Title", List.of("dp"), "changed statement");

        assertThat(fingerprints.verificationFingerprint(first, 1600))
                .isNotEqualTo(fingerprints.verificationFingerprint(changed, 1600));
        assertThat(fingerprints.verificationFingerprint(first, 1600))
                .isNotEqualTo(fingerprints.verificationFingerprint(first, 1800));
    }

    @Test
    void highDifficultyAndManualUnverifiedWarningsAreServerDerived() {
        assertThat(risks.derive("MEDIUM", 1700, false).code()).isEqualTo("STANDARD");
        assertThat(risks.derive("HARD", 1600, false).code()).isEqualTo("HIGH_DIFFICULTY_AI");
        assertThat(risks.derive("EASY", 1701, false).highDifficulty()).isTrue();
        assertThat(risks.derive("EASY", 1000, true).code()).isEqualTo("MANUAL_UNVERIFIED");
        assertThat(risks.derive("EASY", 1000, true).manualUnverified()).isTrue();
    }

    private ProblemDraftWorkingState state(String title, List<String> tags, String statement) {
        return new ProblemDraftWorkingState(
                title, "MEDIUM", statement, "notes", "cpp", "int main(){}", "python",
                "print(0)", "print('1')", null, "plan", tags,
                List.of(new TestCaseDto("1\n", "1\n", true)), 2000, 262144);
    }
}
