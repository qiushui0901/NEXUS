package com.example.requirementrag.versioning;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.VersioningProperties;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.versioning.RequirementSnapshotModels.Entry;
import com.example.requirementrag.versioning.RequirementSnapshotModels.Snapshot;
import com.example.requirementrag.versioning.VersionModels.ManifestStatus;
import com.example.requirementrag.versioning.VersionModels.VersionManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RequirementVersionDiffServiceTest {
    @TempDir
    Path temp;

    @Test
    void comparesLocalIncrementalFixtureWithoutQdrantOrFalseRemovals() throws Exception {
        writeSnapshot("5.0", null, """
                {"entryId":"keep","filename":"base.md","parentOrder":0,"text":"still effective","contentHash":"keep"},
                {"entryId":"change","filename":"base.md","parentOrder":1,"text":"old text","contentHash":"old"}
                """);
        writeSnapshot("5.1", "5.0", """
                {"entryId":"change","filename":"delta.md","parentOrder":0,"text":"new text","contentHash":"new"},
                {"entryId":"add","filename":"delta.md","parentOrder":1,"text":"added","contentHash":"add"}
                """);
        QdrantHybridStore store = mock(QdrantHybridStore.class);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        RequirementSnapshotRepository snapshots = new RequirementSnapshotRepository(new ObjectMapper(),
                new VersioningProperties("unused", temp.toString()));

        var diff = new RequirementVersionDiffService(store, registry, snapshots)
                .compare("game", manifest("5.0"), manifest("5.1"));

        assertThat(diff.availability()).isEqualTo(VersionModels.Availability.AVAILABLE);
        assertThat(diff.added()).isEqualTo(1);
        assertThat(diff.modified()).isEqualTo(1);
        assertThat(diff.removed()).isZero();
        assertThat(diff.changes()).allSatisfy(change -> {
            assertThat(change.filename()).isNotBlank();
            assertThat(change.beforeExcerpt() != null || change.afterExcerpt() != null).isTrue();
        });
        verifyNoInteractions(store, registry);
    }

    @Test
    void comparesMaterializedSnapshotsIncludingExplicitRemoval() {
        QdrantHybridStore store = mock(QdrantHybridStore.class);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        RequirementSnapshotRepository snapshots = mock(RequirementSnapshotRepository.class);
        Snapshot before = snapshot("5.0",
                entry("same", "a.html", 0, "old text", "hash-old"),
                entry("remove", "b.html", 1, "removed", "hash-remove"));
        Snapshot after = snapshot("5.1",
                entry("same", "a.html", 0, "new text", "hash-new"),
                entry("add", "c.html", 2, "added", "hash-add"));
        when(snapshots.materialize("game", "requirements", "5.0")).thenReturn(Optional.of(before));
        when(snapshots.materialize("game", "requirements", "5.1")).thenReturn(Optional.of(after));

        var diff = new RequirementVersionDiffService(store, registry, snapshots)
                .compare("game", manifest("5.0"), manifest("5.1"));

        assertThat(diff.added()).isEqualTo(1);
        assertThat(diff.modified()).isEqualTo(1);
        assertThat(diff.removed()).isEqualTo(1);
        verifyNoInteractions(store, registry);
    }

    @Test
    void qdrantFallbackNeverInfersRemovalFromAnAbsentIncrementalEntry() {
        QdrantHybridStore store = mock(QdrantHybridStore.class);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        RequirementSnapshotRepository snapshots = mock(RequirementSnapshotRepository.class);
        when(snapshots.materialize("game", "requirements", "5.0")).thenReturn(Optional.empty());
        when(snapshots.materialize("game", "requirements", "5.1")).thenReturn(Optional.empty());
        when(registry.resolveRequirementCollection("game")).thenReturn("requirements_game");
        when(store.scrollVersion("requirements_game", "requirements", "5.0")).thenReturn(List.of(
                chunk("old", "a.html", 0, "old text", "hash-old"),
                chunk("absent", "b.html", 1, "still effective", "hash-keep")));
        when(store.scrollVersion("requirements_game", "requirements", "5.1")).thenReturn(List.of(
                chunk("old", "a.html", 0, "new text", "hash-new"),
                chunk("add", "c.html", 2, "added", "hash-add")));

        var diff = new RequirementVersionDiffService(store, registry, snapshots)
                .compare("game", manifest("5.0"), manifest("5.1"));

        assertThat(diff.added()).isEqualTo(1);
        assertThat(diff.modified()).isEqualTo(1);
        assertThat(diff.removed()).isZero();
        assertThat(diff.changes()).extracting(change -> change.type().name())
                .containsExactlyInAnyOrder("ADDED", "MODIFIED");
    }

    private void writeSnapshot(String version, String baseVersion, String entries) throws Exception {
        Path project = temp.resolve("game");
        Files.createDirectories(project);
        String base = baseVersion == null ? "null" : "\"" + baseVersion + "\"";
        Files.writeString(project.resolve(version + ".json"), """
                {
                  "schemaVersion": 1,
                  "projectId": "game",
                  "documentId": "requirements",
                  "requirementVersion": "%s",
                  "baseRequirementVersion": %s,
                  "aliases": ["%s"],
                  "sources": [],
                  "entries": [%s]
                }
                """.formatted(version, base, version, entries));
    }

    private Snapshot snapshot(String version, Entry... entries) {
        return new Snapshot(1, "game", "requirements", version, null, List.of(version), "now",
                List.of(), List.of(entries));
    }

    private Entry entry(String id, String file, int order, String text, String hash) {
        return new Entry(id, file, order, text, hash);
    }

    private ChunkRecord chunk(String parentId, String file, int order, String text, String hash) {
        return new ChunkRecord(parentId + "-1", "requirements", "v", file, parentId,
                text, text, hash, order, 0);
    }

    private VersionManifest manifest(String version) {
        return new VersionManifest(1, "game", version, null, "requirements", version,
                null, null, null, version, null, ManifestStatus.DRAFT, null, null, List.of());
    }
}
