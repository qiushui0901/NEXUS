package com.example.requirementrag.knowledge.build;

import com.example.requirementrag.config.WikiProperties;
import com.example.requirementrag.knowledge.build.KnowledgeDraftModels.DraftMetadata;
import com.example.requirementrag.knowledge.build.KnowledgeDraftModels.DraftStatus;
import com.example.requirementrag.wiki.WikiGenerationService;
import com.example.requirementrag.wiki.WikiModels.GenerationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeDraftLifecycleServiceTest {
    @TempDir
    Path temp;

    private final ObjectMapper mapper = new ObjectMapper();
    private WikiGenerationService wikiGenerationService;
    private KnowledgeDraftLifecycleService service;
    private Path drafts;
    private Path sources;

    @BeforeEach
    void setUp() {
        drafts = temp.resolve("drafts");
        sources = temp.resolve("sources");
        WikiProperties properties = new WikiProperties(temp.resolve("wiki").toString(),
                sources.toString(), drafts.toString());
        wikiGenerationService = mock(WikiGenerationService.class);
        service = new KnowledgeDraftLifecycleService(mapper, properties, wikiGenerationService);
    }

    @Test
    void initializesPersistsListsAndAuditsDraft() throws Exception {
        createArtifacts("build-1", "DRAFT", "source-one");

        DraftMetadata initialized = service.initializeDraft("game", "6.0", "build-1", "alice",
                "2026-07-28T01:00:00Z");
        DraftMetadata reviewing = service.transition("game", "6.0", "build-1", DraftStatus.IN_REVIEW,
                "bob", "请评审");

        assertThat(initialized.status()).isEqualTo(DraftStatus.DRAFT);
        assertThat(initialized.revision()).isZero();
        assertThat(initialized.history()).hasSize(1);
        assertThat(initialized.history().get(0).actor()).isEqualTo("alice");
        assertThat(reviewing.status()).isEqualTo(DraftStatus.IN_REVIEW);
        assertThat(reviewing.revision()).isEqualTo(1);
        assertThat(reviewing.history().get(reviewing.history().size() - 1).comment()).isEqualTo("请评审");
        assertThat(service.get("game", "6.0", "build-1")).isEqualTo(reviewing);
        assertThat(service.list("game", "6.0")).containsExactly(reviewing);
        assertThat(drafts.resolve("game/6.0/build-1/review.json")).isRegularFile();
    }

    @Test
    void enforcesLegalTransitionsAndPathSafety() throws Exception {
        createArtifacts("build-1", "DRAFT", "source-one");
        service.initializeDraft("game", "6.0", "build-1", "alice", null);

        assertThatThrownBy(() -> service.transition("game", "6.0", "build-1", DraftStatus.APPROVED,
                "alice", null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DRAFT -> APPROVED");
        assertThatThrownBy(() -> service.transition("game", "6.0", "build-1", DraftStatus.PUBLISHED,
                "alice", null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("发布操作");
        assertThatThrownBy(() -> service.get("../game", "6.0", "build-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("projectId");
        assertThatThrownBy(() -> service.get("game", "6.0", "missing"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsUnapprovedAndNoChangesDrafts() throws Exception {
        createArtifacts("draft", "DRAFT", "source-one");
        service.initializeDraft("game", "6.0", "draft", "alice", null);
        assertThatThrownBy(() -> service.publish("game", "6.0", "draft", "alice", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("APPROVED");

        createArtifacts("no-change", "NO_CHANGES", "source-two");
        approve("no-change");
        assertThatThrownBy(() -> service.publish("game", "6.0", "no-change", "alice", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NO_CHANGES");
        assertThat(sources.resolve("game-v6.0.json")).doesNotExist();
    }

    @Test
    void publishesApprovedDraftAndFirstPublicationCannotRollback() throws Exception {
        createArtifacts("build-1", "DRAFT", "source-one");
        approve("build-1");
        GenerationResult generated = generation("generated-one");
        when(wikiGenerationService.generate("game", "6.0")).thenReturn(generated);

        var result = service.publish("game", "6.0", "build-1", "publisher", "上线");

        assertThat(result.wiki()).isEqualTo(generated);
        assertThat(result.draft().status()).isEqualTo(DraftStatus.PUBLISHED);
        assertThat(result.draft().publication().publishedBy()).isEqualTo("publisher");
        assertThat(result.draft().publication().previousSnapshotId()).isBlank();
        assertThat(Files.readString(sources.resolve("game-v6.0.json"))).contains("source-one");
        assertThatThrownBy(() -> service.rollback("game", "6.0", "build-1", "publisher", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("没有可回滚");
    }

    @Test
    void snapshotsPreviousPublicationAndRollsBackOnlyOnce() throws Exception {
        when(wikiGenerationService.generate("game", "6.0")).thenReturn(generation("generated"));
        createArtifacts("build-1", "DRAFT", "source-one");
        approve("build-1");
        service.publish("game", "6.0", "build-1", "alice", null);

        createArtifacts("build-2", "DRAFT", "source-two");
        approve("build-2");
        var published = service.publish("game", "6.0", "build-2", "bob", null);
        assertThat(Files.readString(sources.resolve("game-v6.0.json"))).contains("source-two");
        assertThat(published.draft().publication().previousSnapshotId()).isNotBlank();

        var rolledBack = service.rollback("game", "6.0", "build-2", "carol", "撤回");

        assertThat(Files.readString(sources.resolve("game-v6.0.json"))).contains("source-one");
        assertThat(rolledBack.draft().publication().rolledBackBy()).isEqualTo("carol");
        assertThat(rolledBack.draft().publication().rollbackComment()).isEqualTo("撤回");
        assertThatThrownBy(() -> service.rollback("game", "6.0", "build-2", "carol", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不可重复回滚");
    }

    @Test
    void restoresPreviousSourceWhenWikiGenerationFails() throws Exception {
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("game-v6.0.json"), "{\"marker\":\"old-source\"}");
        createArtifacts("build-2", "DRAFT", "new-source");
        approve("build-2");
        RuntimeException generationFailure = new IllegalStateException("generation failed");
        when(wikiGenerationService.generate("game", "6.0"))
                .thenThrow(generationFailure)
                .thenReturn(generation("restored"));

        assertThatThrownBy(() -> service.publish("game", "6.0", "build-2", "bob", null))
                .isSameAs(generationFailure);

        assertThat(Files.readString(sources.resolve("game-v6.0.json"))).contains("old-source");
        assertThat(service.get("game", "6.0", "build-2").status()).isEqualTo(DraftStatus.APPROVED);
        verify(wikiGenerationService, times(2)).generate("game", "6.0");
    }

    private DraftMetadata approve(String buildId) {
        service.initializeDraft("game", "6.0", buildId, "alice", null);
        service.transition("game", "6.0", buildId, DraftStatus.IN_REVIEW, "reviewer", null);
        return service.transition("game", "6.0", buildId, DraftStatus.APPROVED, "reviewer", "通过");
    }

    private void createArtifacts(String buildId, String status, String marker) throws Exception {
        Path directory = drafts.resolve("game/6.0").resolve(buildId);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("build.json"), "{\"status\":\"" + status + "\"}");
        Files.writeString(directory.resolve("wiki-source.json"), "{\"marker\":\"" + marker + "\"}");
    }

    private GenerationResult generation(String outputPath) {
        return new GenerationResult("game", "6.0", 1, outputPath, "2026-07-28T02:00:00Z");
    }
}
