package com.example.requirementrag.versioning;

import com.example.requirementrag.config.VersioningProperties;
import com.example.requirementrag.versioning.VersionModels.ManifestStatus;
import com.example.requirementrag.versioning.VersionModels.TestCaseSnapshot;
import com.example.requirementrag.versioning.VersionModels.TestCaseStatus;
import com.example.requirementrag.versioning.VersionModels.TestRunStatus;
import com.example.requirementrag.versioning.VersionModels.TestSnapshot;
import com.example.requirementrag.versioning.VersionModels.VersionManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionManifestServiceTest {
    @TempDir Path temp;

    @Test
    void savesUpdatesAndListsVersionManifestsWithoutVectorData() throws Exception {
        VersionManifestService service = new VersionManifestService(new ObjectMapper(),
                new VersioningProperties(temp.toString()));
        VersionManifest first = service.save(manifest("5.0", null, "aaaaaaa", "baseline"));
        service.save(manifest("5.1", "5.0", "bbbbbbb", "target"));
        VersionManifest updated = service.save(manifest("5.0", null, "ccccccc", "updated"));

        assertThat(updated.createdAt()).isEqualTo(first.createdAt());
        assertThat(updated.updatedAt()).isNotBlank();
        assertThat(service.get("game", "5.0").codeCommit()).isEqualTo("ccccccc");
        assertThat(service.list("game")).extracting(VersionManifest::version).containsExactly("5.1", "5.0");
        String json = Files.readString(temp.resolve("game/5.1.json"));
        assertThat(json).doesNotContainIgnoringCase("vector", "embedding", "qdrant", "credential");
        try (var files = Files.list(temp.resolve("game"))) {
            assertThat(files.map(path -> path.getFileName().toString())).noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    @Test
    void rejectsUnsafeIdentifiersCommitsAndInvalidTestTotals() {
        VersionManifestService service = new VersionManifestService(new ObjectMapper(),
                new VersioningProperties(temp.toString()));
        assertThatThrownBy(() -> service.save(new VersionManifest(1, "../game", "5.1", null,
                null, null, null, "abcdef1", null, null, null, ManifestStatus.DRAFT,
                null, null, List.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save(manifest("5.1", null, "HEAD;rm", "bad")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Git commit SHA");

        TestSnapshot duplicateCases = new TestSnapshot("report-1", TestRunStatus.PASSED, 2, 2, 0, 0, List.of(
                new TestCaseSnapshot("case-1", "first", TestCaseStatus.PASSED),
                new TestCaseSnapshot("case-1", "duplicate", TestCaseStatus.PASSED)));
        VersionManifest duplicateManifest = new VersionManifest(1, "game", "5.1", null, null, null,
                null, "abcdef1", duplicateCases, null, null, ManifestStatus.DRAFT, null, null, List.of());
        assertThatThrownBy(() -> service.save(duplicateManifest))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("caseId 重复");
    }

    private VersionManifest manifest(String version, String baseVersion, String commit, String note) {
        return new VersionManifest(0, "game", version, baseVersion, "requirements", version,
                baseVersion == null ? null : "aaaaaaa", commit, null, version, null,
                ManifestStatus.DRAFT, null, null, List.of(note));
    }
}
