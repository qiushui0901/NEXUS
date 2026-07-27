package com.example.requirementrag.versioning;

import com.example.requirementrag.config.VersioningProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequirementSnapshotRepositoryTest {
    @TempDir
    Path temp;

    @Test
    void committedSnapshotsCoverTheCurrentComparisonChainWithoutVectorData() throws Exception {
        RequirementSnapshotRepository repository = new RequirementSnapshotRepository(new ObjectMapper(),
                new VersioningProperties("unused", "data/requirement-snapshots"));

        var baseline = repository.findForBusinessVersion("immortal-game-service", "5.0.2");
        var target = repository.findForBusinessVersion("immortal-game-service", "5.1");

        assertThat(repository.list("immortal-game-service")).hasSize(20);
        assertThat(baseline).isPresent().get().extracting(snapshot -> snapshot.requirementVersion())
                .isEqualTo("5.0");
        assertThat(target).isPresent().get().extracting(snapshot -> snapshot.requirementVersion())
                .isEqualTo("5.1");
        assertThat(baseline.orElseThrow().entries()).isNotEmpty();
        assertThat(target.orElseThrow().entries()).isNotEmpty();
        String json = Files.readString(Path.of("data/requirement-snapshots/immortal-game-service/5.1.json"));
        assertThat(json).doesNotContain("\"vector\"", "\"embedding\"", "\"apiKey\"", "\"password\"");
    }

    @Test
    void rejectsDuplicateEntryIds() throws Exception {
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        Files.writeString(project.resolve("5.1.json"), """
                {
                  "schemaVersion": 1,
                  "projectId": "game",
                  "documentId": "requirements",
                  "requirementVersion": "5.1",
                  "aliases": ["5.1"],
                  "sources": [],
                  "entries": [
                    {"entryId":"same","filename":"a.html","parentOrder":0,"text":"one","contentHash":"a"},
                    {"entryId":"same","filename":"b.html","parentOrder":1,"text":"two","contentHash":"b"}
                  ]
                }
                """);
        RequirementSnapshotRepository repository = new RequirementSnapshotRepository(new ObjectMapper(),
                new VersioningProperties("unused", temp.toString()));

        assertThatThrownBy(() -> repository.list("game"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("读取需求版本快照失败");
    }
}
