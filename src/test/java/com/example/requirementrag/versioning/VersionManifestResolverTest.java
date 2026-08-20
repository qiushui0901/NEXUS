package com.example.requirementrag.versioning;

import com.example.requirementrag.versioning.RequirementSnapshotModels.Snapshot;
import com.example.requirementrag.versioning.VersionModels.ManifestStatus;
import com.example.requirementrag.versioning.VersionModels.VersionManifest;
import com.example.requirementrag.wiki.WikiModels.VersionIndex;
import com.example.requirementrag.wiki.WikiRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VersionManifestResolverTest {
    @Test
    void synthesizesRequirementReferencesAndBusinessBaseVersionFromWikiChain() {
        VersionManifestService manifests = mock(VersionManifestService.class);
        WikiRepository wiki = mock(WikiRepository.class);
        RequirementSnapshotRepository snapshots = mock(RequirementSnapshotRepository.class);
        VersionIndex baseline = index("5.0.2", "aaaaaaa", "bbbbbbb");
        VersionIndex target = index("5.1", "bbbbbbb", "ccccccc");
        when(wiki.listVersions("game")).thenReturn(List.of(target, baseline));
        when(manifests.list("game")).thenReturn(List.of());
        when(snapshots.list("game")).thenReturn(List.of(
                snapshot("5.0", "5.0.2"), snapshot("5.1", "5.1")));

        List<VersionManifest> result = new VersionManifestResolver(manifests, wiki, snapshots).list("game");

        assertThat(result).extracting(VersionManifest::version).containsExactly("5.1", "5.0.2");
        assertThat(result.get(0).baseVersion()).isEqualTo("5.0.2");
        assertThat(result.get(0).requirementDocumentId()).isEqualTo("requirements");
        assertThat(result.get(0).requirementVersion()).isEqualTo("5.1");
        assertThat(result.get(1).requirementVersion()).isEqualTo("5.0");
    }

    @Test
    void enrichesAFormalManifestThatDoesNotYetContainRequirementReferences() {
        VersionManifestService manifests = mock(VersionManifestService.class);
        WikiRepository wiki = mock(WikiRepository.class);
        RequirementSnapshotRepository snapshots = mock(RequirementSnapshotRepository.class);
        VersionManifest formal = new VersionManifest(1, "game", "5.1", "5.0.2", null, null,
                "bbbbbbb", "ccccccc", null, "5.1", "build", ManifestStatus.RELEASED,
                "created", "updated", List.of("formal"));
        when(wiki.listVersions("game")).thenReturn(List.of());
        when(manifests.list("game")).thenReturn(List.of(formal));
        when(snapshots.list("game")).thenReturn(List.of(snapshot("5.1", "5.1")));

        VersionManifest resolved = new VersionManifestResolver(manifests, wiki, snapshots).get("game", "5.1");

        assertThat(resolved.requirementDocumentId()).isEqualTo("requirements");
        assertThat(resolved.requirementVersion()).isEqualTo("5.1");
        assertThat(resolved.notes()).contains("formal", "已从需求快照补齐需求版本引用 5.1");
    }

    @Test
    void enrichmentPreservesSchemaV3ProductAndRepositoryBaselines() {
        VersionManifestService manifests = mock(VersionManifestService.class);
        WikiRepository wiki = mock(WikiRepository.class);
        RequirementSnapshotRepository snapshots = mock(RequirementSnapshotRepository.class);
        var baseline = new VersionModels.RepositoryBaseline(
                "immortal-game-service", "PROJECT", "5.2.0", "c".repeat(40), "code_chunks");
        VersionManifest formal = new VersionManifest(3, "immortal", "v5.2.0", null,
                null, null, null, null, "v5.2.0", List.of(baseline),
                null, "v5.2.0", "build", ManifestStatus.RELEASED,
                "created", "updated", List.of("formal"));
        when(wiki.listVersions("immortal")).thenReturn(List.of());
        when(manifests.list("immortal")).thenReturn(List.of(formal));
        when(snapshots.list("immortal")).thenReturn(List.of(snapshot("5.1", "v5.2.0")));

        VersionManifest resolved =
                new VersionManifestResolver(manifests, wiki, snapshots).get("immortal", "v5.2.0");

        assertThat(resolved.productVersion()).isEqualTo("v5.2.0");
        assertThat(resolved.repositoryBaselines()).containsExactly(baseline);
        assertThat(resolved.requirementVersion()).isEqualTo("5.1");
    }

    private VersionIndex index(String version, String baseCommit, String codeCommit) {
        return new VersionIndex(1, "game", "Game", version, version, baseCommit, codeCommit, "now", List.of());
    }

    private Snapshot snapshot(String version, String alias) {
        return new Snapshot(1, "game", "requirements", version, null, List.of(alias), "now",
                List.of(), List.of());
    }
}
