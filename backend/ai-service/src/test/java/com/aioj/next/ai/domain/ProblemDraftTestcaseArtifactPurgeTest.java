package com.aioj.next.ai.domain;

import com.aioj.next.ai.config.AiProperties;
import com.aioj.next.ai.config.InternalApiProperties;
import com.aioj.next.ai.persistence.mapper.ProblemDraftTestcaseArtifactMapper;
import com.aioj.next.common.http.InternalRestClientFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Plan S6: retention deletes a retired package file inside the storage root and nothing outside it. */
class ProblemDraftTestcaseArtifactPurgeTest {
    @TempDir
    Path temp;

    @Test
    void theFileGoesAndTheRowStaysMarked() throws IOException {
        Path root = Files.createDirectories(temp.resolve("artifacts"));
        Path file = Files.writeString(Files.createDirectories(root.resolve("9")).resolve("41.zip"), "zip");
        ProblemDraftTestcaseArtifactMapper mapper = mock(ProblemDraftTestcaseArtifactMapper.class);

        service(root, mapper).purgeFile(41L, "9/41.zip");

        assertThat(file).doesNotExist();
        verify(mapper).update(any(), any());
    }

    @Test
    void aPathOutsideTheRootIsNeverTouchedAndAMissingFileIsNoError() throws IOException {
        Path root = Files.createDirectories(temp.resolve("artifacts"));
        Path outside = Files.writeString(temp.resolve("outside.zip"), "keep");
        ProblemDraftTestcaseArtifactMapper mapper = mock(ProblemDraftTestcaseArtifactMapper.class);
        ProblemDraftTestcaseArtifactService service = service(root, mapper);

        service.purgeFile(41L, "../outside.zip");
        service.purgeFile(42L, "9/gone.zip");
        service.purgeFile(43L, "");

        assertThat(outside).exists();
        verify(mapper, times(3)).update(any(), any());
    }

    private static ProblemDraftTestcaseArtifactService service(Path root, ProblemDraftTestcaseArtifactMapper mapper) {
        AiProperties properties = new AiProperties();
        properties.getProblemDraft().setTestcaseArtifactStorageRoot(root.toString());
        return new ProblemDraftTestcaseArtifactService(mapper, new ObjectMapper(), properties,
                new InternalRestClientFactory(new InternalApiProperties()));
    }
}
