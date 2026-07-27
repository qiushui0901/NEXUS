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
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RequirementVersionDiffServiceTest {
    @Test
    void comparesTheCommittedCurrentRequirementChainWithoutQdrant() {
        QdrantHybridStore store = mock(QdrantHybridStore.class);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        RequirementSnapshotRepository snapshots = new RequirementSnapshotRepository(new ObjectMapper(),
                new VersioningProperties("unused", "data/requirement-snapshots"));
        VersionManifest from = new VersionManifest(1, "immortal-game-service", "5.0.2", null,
                "fengshen", "5.0", null, null, null, "5.0.2", null,
                ManifestStatus.RELEASED, null, null, List.of());
        VersionManifest to = new VersionManifest(1, "immortal-game-service", "5.1", "5.0.2",
                "fengshen", "5.1", null, null, null, "5.1", null,
                ManifestStatus.RELEASED, null, null, List.of());

        var diff = new RequirementVersionDiffService(store, registry, snapshots)
                .compare("immortal-game-service", from, to);

        assertThat(diff.availability()).isEqualTo(VersionModels.Availability.AVAILABLE);
        assertThat(diff.added() + diff.modified() + diff.removed()).isPositive();
        assertThat(diff.changes()).allSatisfy(change -> {
            assertThat(change.filename()).isNotBlank();
            assertThat(change.beforeExcerpt() != null || change.afterExcerpt() != null).isTrue();
        });
        verifyNoInteractions(store, registry);
    }

    @Test
    void prefersReviewableSnapshotsWithoutReadingTheVectorStore() {
        QdrantHybridStore store = mock(QdrantHybridStore.class);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        RequirementSnapshotRepository snapshots = mock(RequirementSnapshotRepository.class);
        Snapshot before = snapshot("5.0",
                entry("same", "a.html", 0, "old text", "hash-old"),
                entry("remove", "b.html", 1, "removed", "hash-remove"));
        Snapshot after = snapshot("5.1",
                entry("same", "a.html", 0, "new text", "hash-new"),
                entry("add", "c.html", 2, "added", "hash-add"));
        when(snapshots.find("game", "requirements", "5.0")).thenReturn(Optional.of(before));
        when(snapshots.find("game", "requirements", "5.1")).thenReturn(Optional.of(after));

        var diff = new RequirementVersionDiffService(store, registry, snapshots)
                .compare("game", manifest("5.0"), manifest("5.1"));

        assertThat(diff.added()).isEqualTo(1);
        assertThat(diff.modified()).isEqualTo(1);
        assertThat(diff.removed()).isEqualTo(1);
        verifyNoInteractions(store, registry);
    }

    @Test
    void fallsBackToPayloadRecordsWhenSnapshotsAreMissing() {
        QdrantHybridStore store = mock(QdrantHybridStore.class);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        RequirementSnapshotRepository snapshots = mock(RequirementSnapshotRepository.class);
        when(snapshots.find("game", "requirements", "5.0")).thenReturn(Optional.empty());
        when(snapshots.find("game", "requirements", "5.1")).thenReturn(Optional.empty());
        when(registry.resolveRequirementCollection("game")).thenReturn("requirements_game");
        when(store.scrollVersion("requirements_game", "requirements", "5.0")).thenReturn(List.of(
                chunk("old", "a.html", 0, "old text", "hash-old"),
                chunk("remove", "b.html", 1, "removed", "hash-remove")));
        when(store.scrollVersion("requirements_game", "requirements", "5.1")).thenReturn(List.of(
                chunk("old", "a.html", 0, "new text", "hash-new"),
                chunk("add", "c.html", 2, "added", "hash-add")));

        var diff = new RequirementVersionDiffService(store, registry, snapshots)
                .compare("game", manifest("5.0"), manifest("5.1"));

        assertThat(diff.added()).isEqualTo(1);
        assertThat(diff.modified()).isEqualTo(1);
        assertThat(diff.removed()).isEqualTo(1);
        assertThat(diff.changes()).extracting(change -> change.type().name())
                .containsExactlyInAnyOrder("ADDED", "MODIFIED", "REMOVED");
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
