package com.example.requirementrag.versioning;

import com.example.requirementrag.config.VersioningProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequirementSnapshotRepositoryTest {
    @TempDir
    Path temp;

    @Test
    void missingLocalSnapshotDirectoryIsAValidEmptyState() {
        RequirementSnapshotRepository repository = new RequirementSnapshotRepository(new ObjectMapper(),
                new VersioningProperties("unused", temp.resolve("not-generated").toString()));

        assertThat(repository.list("game")).isEmpty();
        assertThat(repository.findForBusinessVersion("game", "5.1")).isEmpty();
        assertThat(repository.materialize("game", "requirements", "5.1")).isEmpty();
    }

    @Test
    void materializesIncrementalEntriesAndOnlyAppliesExplicitRemoval() throws Exception {
        write("game", "1.0", null, """
                {"entryId":"keep","filename":"base.md","parentOrder":0,"text":"历史需求","contentHash":"keep"},
                {"entryId":"change","filename":"base.md","parentOrder":1,"text":"旧内容","contentHash":"old"},
                {"entryId":"remove","filename":"base.md","parentOrder":2,"text":"待删除需求","contentHash":"remove"}
                """);
        write("game", "1.1", "1.0", """
                {"entryId":"change","filename":"delta.md","parentOrder":0,"text":"新内容","contentHash":"new"},
                {"entryId":"word-only","filename":"delta.md","parentOrder":1,"text":"业务正文包含删除按钮，但不是删除需求","contentHash":"word"},
                {"entryId":"remove","filename":"delta.md","parentOrder":2,"text":"明确删除历史需求","contentHash":"remove-event","operation":"REMOVE"}
                """);
        RequirementSnapshotRepository repository = repository();

        var materialized = repository.materialize("game", "requirements", "1.1").orElseThrow();

        assertThat(materialized.entries()).extracting(entry -> entry.entryId())
                .containsExactly("keep", "change", "word-only")
                .doesNotContain("remove");
        assertThat(materialized.entries()).filteredOn(entry -> entry.entryId().equals("change"))
                .singleElement().extracting(entry -> entry.text()).isEqualTo("新内容");
        assertThat(materialized.entries()).filteredOn(entry -> entry.entryId().equals("word-only"))
                .singleElement().extracting(entry -> entry.effectiveOperation())
                .isEqualTo(RequirementSnapshotModels.Operation.UPSERT);
    }

    @Test
    void rejectsMissingBaselineAndInheritanceCycles() throws Exception {
        write("missing", "1.1", "1.0",
                "{\"entryId\":\"one\",\"filename\":\"a.md\",\"parentOrder\":0,\"text\":\"one\",\"contentHash\":\"one\"}");
        write("cycle", "1.0", "1.1",
                "{\"entryId\":\"one\",\"filename\":\"a.md\",\"parentOrder\":0,\"text\":\"one\",\"contentHash\":\"one\"}");
        write("cycle", "1.1", "1.0",
                "{\"entryId\":\"two\",\"filename\":\"b.md\",\"parentOrder\":0,\"text\":\"two\",\"contentHash\":\"two\"}");
        RequirementSnapshotRepository repository = repository();

        assertThatThrownBy(() -> repository.materialize("missing", "requirements", "1.1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("需求快照基线不存在");
        assertThatThrownBy(() -> repository.materialize("cycle", "requirements", "1.1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("需求快照继承链存在循环");
    }

    @Test
    void rejectsDuplicateEntryIds() throws Exception {
        write("game", "5.1", null, """
                {"entryId":"same","filename":"a.html","parentOrder":0,"text":"one","contentHash":"a"},
                {"entryId":"same","filename":"b.html","parentOrder":1,"text":"two","contentHash":"b"}
                """);
        RequirementSnapshotRepository repository = repository();

        assertThatThrownBy(() -> repository.list("game"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("读取需求版本快照失败");
    }

    private RequirementSnapshotRepository repository() {
        return new RequirementSnapshotRepository(new ObjectMapper(),
                new VersioningProperties("unused", temp.toString()));
    }

    private void write(String projectId, String version, String baseVersion, String entries) throws Exception {
        Path project = temp.resolve(projectId);
        Files.createDirectories(project);
        String base = baseVersion == null ? "null" : "\"" + baseVersion + "\"";
        Files.writeString(project.resolve(version + ".json"), """
                {
                  "schemaVersion": 1,
                  "projectId": "%s",
                  "documentId": "requirements",
                  "requirementVersion": "%s",
                  "baseRequirementVersion": %s,
                  "aliases": ["%s"],
                  "sources": [],
                  "entries": [%s]
                }
                """.formatted(projectId, version, base, version, entries));
    }
}
