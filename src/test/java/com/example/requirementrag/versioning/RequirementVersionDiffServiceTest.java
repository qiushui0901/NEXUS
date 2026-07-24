package com.example.requirementrag.versioning;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.versioning.VersionModels.ManifestStatus;
import com.example.requirementrag.versioning.VersionModels.VersionManifest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequirementVersionDiffServiceTest {
    @Test
    void detectsAddedModifiedAndRemovedParentsFromPayloadRecords() {
        QdrantHybridStore store = mock(QdrantHybridStore.class);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        when(registry.resolveRequirementCollection("game")).thenReturn("requirements_game");
        when(store.scrollVersion("requirements_game", "requirements", "5.0")).thenReturn(List.of(
                chunk("old", "a.html", 0, "old text", "hash-old"),
                chunk("remove", "b.html", 1, "removed", "hash-remove")));
        when(store.scrollVersion("requirements_game", "requirements", "5.1")).thenReturn(List.of(
                chunk("old", "a.html", 0, "new text", "hash-new"),
                chunk("add", "c.html", 2, "added", "hash-add")));

        var diff = new RequirementVersionDiffService(store, registry)
                .compare("game", manifest("5.0"), manifest("5.1"));

        assertThat(diff.added()).isEqualTo(1);
        assertThat(diff.modified()).isEqualTo(1);
        assertThat(diff.removed()).isEqualTo(1);
        assertThat(diff.changes()).extracting(change -> change.type().name())
                .containsExactlyInAnyOrder("ADDED", "MODIFIED", "REMOVED");
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
