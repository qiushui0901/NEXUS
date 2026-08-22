package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.BuildRequest;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractedEntity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractedRelation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractionResult;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.versioning.RequirementSnapshotModels.Entry;
import com.example.requirementrag.versioning.RequirementSnapshotModels.Snapshot;
import com.example.requirementrag.versioning.RequirementSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequirementGraphBuildServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void buildsVersionScopedStableDraftFromMaterializedRequirementSnapshot() {
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(),
                new RequirementGraphProperties(true, true, true, tempDir.resolve("graph.db").toString(),
                        20, 30, 20_000, 2, 40, "model", "v1"));
        RequirementGraphExtractionService extractor = mock(RequirementGraphExtractionService.class);
        RequirementSnapshotRepository snapshots = mock(RequirementSnapshotRepository.class);
        com.example.requirementrag.retrieval.QdrantHybridStore qdrant =
                mock(com.example.requirementrag.retrieval.QdrantHybridStore.class);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        when(registry.resolveRequirementCollection("orders")).thenReturn("requirements_orders");
        RequirementGraphProperties properties = new RequirementGraphProperties(
                true, true, true, tempDir.resolve("graph.db").toString(), 20, 30, 20_000, 2, 40, "model", "v1");
        Snapshot source = new Snapshot(1, "orders", "requirements", "2.0", null, List.of(),
                "2026-08-20T00:00:00Z", List.of(), List.of(
                        new Entry("entry-1", "orders.md", 0,
                                "玩家购买成长基金并影响库存。", "hash-1")));
        when(snapshots.materialize("orders", "requirements", "2.0")).thenReturn(Optional.of(source));
        when(extractor.extract(any())).thenReturn(new ExtractionResult(
                List.of(
                        new ExtractedEntity("e1", "FEATURE", "成长基金", List.of(), "",
                                List.of("玩家购买成长基金"), 0.9),
                        new ExtractedEntity("e2", "MODULE", "库存", List.of(), "",
                                List.of("影响库存"), 0.8)),
                List.of(new ExtractedRelation("e1", "AFFECTS_MODULE", "e2", "成长基金影响库存",
                        List.of("玩家购买成长基金并影响库存"), 0.8)), List.of()));

        RequirementGraphBuildService service = new RequirementGraphBuildService(
                store, extractor, snapshots, qdrant, registry, properties);
        BuildRequest request = new BuildRequest("orders", "requirements", "2.0", "requirements_orders");

        GraphSnapshot first = service.build(request);
        GraphSnapshot second = service.build(request);

        assertThat(first.id()).isEqualTo(second.id());
        assertThat(first.status()).isEqualTo(RequirementGraphModels.SnapshotStatus.REVIEW_REQUIRED);
        assertThat(first.entityCount()).isEqualTo(2);
        assertThat(first.relationCount()).isEqualTo(1);
        assertThat(store.findLatest("orders", "requirements", "2.0")).contains(second);
    }

    @Test
    void rebuildAfterPublishReusesImmutablePublishedSnapshot() {
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(),
                new RequirementGraphProperties(true, true, true, tempDir.resolve("graph-reuse.db").toString(),
                        20, 30, 20_000, 2, 40, "model", "v1"));
        RequirementGraphExtractionService extractor = mock(RequirementGraphExtractionService.class);
        RequirementSnapshotRepository snapshots = mock(RequirementSnapshotRepository.class);
        com.example.requirementrag.retrieval.QdrantHybridStore qdrant =
                mock(com.example.requirementrag.retrieval.QdrantHybridStore.class);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        when(registry.resolveRequirementCollection("orders")).thenReturn("requirements_orders");
        RequirementGraphProperties properties = new RequirementGraphProperties(
                true, true, true, tempDir.resolve("graph-reuse.db").toString(), 20, 30, 20_000, 2, 40, "model", "v1");
        Snapshot source = new Snapshot(1, "orders", "requirements", "2.0", null, List.of(),
                "2026-08-20T00:00:00Z", List.of(), List.of(
                        new Entry("entry-1", "orders.md", 0, "玩家购买成长基金并影响库存。", "hash-1")));
        when(snapshots.materialize("orders", "requirements", "2.0")).thenReturn(Optional.of(source));
        when(extractor.extract(any())).thenReturn(new ExtractionResult(
                List.of(new ExtractedEntity("e1", "FEATURE", "成长基金", List.of(), "", List.of("玩家购买成长基金"), 0.9),
                        new ExtractedEntity("e2", "MODULE", "库存", List.of(), "", List.of("影响库存"), 0.8)),
                List.of(new ExtractedRelation("e1", "AFFECTS_MODULE", "e2", "成长基金影响库存",
                        List.of("玩家购买成长基金并影响库存"), 0.8)), List.of()));
        RequirementGraphBuildService service = new RequirementGraphBuildService(
                store, extractor, snapshots, qdrant, registry, properties);
        BuildRequest request = new BuildRequest("orders", "requirements", "2.0", "requirements_orders");
        GraphSnapshot built = service.build(request);
        store.updateStatus(built.id(), RequirementGraphModels.SnapshotStatus.PUBLISHED, null);

        GraphSnapshot rebuilt = service.build(request);

        assertThat(rebuilt.id()).isEqualTo(built.id());
        assertThat(rebuilt.status()).isEqualTo(RequirementGraphModels.SnapshotStatus.PUBLISHED);
        assertThat(store.findLatest("orders", "requirements", "2.0")).contains(rebuilt);
    }

    @Test
    void resumeFromPublishedSnapshotRejected() {
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(),
                new RequirementGraphProperties(true, true, true, tempDir.resolve("graph-resume-pub.db").toString(),
                        20, 30, 20_000, 2, 40, "model", "v1"));
        RequirementGraphExtractionService extractor = mock(RequirementGraphExtractionService.class);
        RequirementSnapshotRepository snapshots = mock(RequirementSnapshotRepository.class);
        com.example.requirementrag.retrieval.QdrantHybridStore qdrant =
                mock(com.example.requirementrag.retrieval.QdrantHybridStore.class);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        when(registry.resolveRequirementCollection("orders")).thenReturn("requirements_orders");
        RequirementGraphProperties properties = new RequirementGraphProperties(
                true, true, true, tempDir.resolve("graph-resume-pub.db").toString(), 20, 30, 20_000, 2, 40, "model", "v1");
        Snapshot source = new Snapshot(1, "orders", "requirements", "2.0", null, List.of(),
                "2026-08-20T00:00:00Z", List.of(), List.of(
                        new Entry("entry-1", "orders.md", 0, "玩家购买成长基金并影响库存。", "hash-1")));
        when(snapshots.materialize("orders", "requirements", "2.0")).thenReturn(Optional.of(source));
        when(extractor.extract(any())).thenReturn(new ExtractionResult(
                List.of(new ExtractedEntity("e1", "FEATURE", "成长基金", List.of(), "", List.of("玩家购买成长基金"), 0.9)),
                List.of(), List.of()));
        RequirementGraphBuildService service = new RequirementGraphBuildService(
                store, extractor, snapshots, qdrant, registry, properties);
        GraphSnapshot built = service.build(new BuildRequest("orders", "requirements", "2.0", "requirements_orders"));
        store.updateStatus(built.id(), RequirementGraphModels.SnapshotStatus.PUBLISHED, null);

        assertThatThrownBy(() -> service.build(new BuildRequest("orders", "requirements", "2.0",
                "requirements_orders", built.id(), false)))
                .isInstanceOf(RequirementGraphException.class)
                .satisfies(exception -> assertThat(((RequirementGraphException) exception).code())
                        .isEqualTo("GRAPH_SNAPSHOT_IMMUTABLE"));
    }

    @Test
    void tokenBudgetStopsFurtherModelCalls() {
        RequirementGraphProperties tokenProperties = tokenProperties();
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(), tokenProperties);
        RequirementGraphExtractionService extractor = mock(RequirementGraphExtractionService.class);
        RequirementSnapshotRepository snapshots = mock(RequirementSnapshotRepository.class);
        com.example.requirementrag.retrieval.QdrantHybridStore qdrant =
                mock(com.example.requirementrag.retrieval.QdrantHybridStore.class);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        when(registry.resolveRequirementCollection("orders")).thenReturn("requirements_orders");
        Snapshot source = new Snapshot(1, "orders", "requirements", "2.0", null, List.of(),
                "2026-08-20T00:00:00Z", List.of(), List.of(
                        new Entry("entry-1", "orders.md", 0,
                                "玩家购买成长基金并影响库存。", "hash-1")));
        when(snapshots.materialize("orders", "requirements", "2.0")).thenReturn(Optional.of(source));

        RequirementGraphBuildService service = new RequirementGraphBuildService(
                store, extractor, snapshots, qdrant, registry, tokenProperties);
        assertThatThrownBy(() -> service.build(new BuildRequest("orders", "requirements", "2.0",
                "requirements_orders")))
                .isInstanceOf(RequirementGraphBuildFailureException.class);
        verify(extractor, never()).extract(any());
    }

    private RequirementGraphProperties tokenProperties() {
        return new RequirementGraphProperties(
                true, true, true, tempDir.resolve("graph-token.db").toString(),
                20, 30, 20_000, 2, 40, "model", "v1", "v1", 2, 400, 500, 500, 2, 900,
                100, 2, 10_000, false, false, false, false, false, false,
                "INTERNAL", "configured", false, java.util.Map.of());
    }
}
