package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Entity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.EntityStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Relation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationType;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchMode;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchRequest;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchResponse;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequirementGraphSearchServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void localSearchTraversesRelationsAndBackfillsQdrantEvidence() {
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(),
                new RequirementGraphProperties(true, true, true, tempDir.resolve("graph.db").toString(),
                        20, 30, 20_000, 2, 40, "model", "v1"));
        Instant now = Instant.now();
        GraphSnapshot snapshot = new GraphSnapshot("reqgraph:search", "orders", "requirements", "2.0",
                "source", "model", "v1", SnapshotStatus.PUBLISHED, 2, 1, now, now, now);
        store.saveSnapshot(snapshot);
        Entity feature = entity("entity:feature", snapshot.id(), "取消订单", RequirementGraphModels.EntityType.FEATURE);
        Entity inventory = entity("entity:inventory", snapshot.id(), "库存", RequirementGraphModels.EntityType.MODULE);
        Relation relation = new Relation("relation:search", snapshot.id(), feature.id(),
                RelationType.AFFECTS_MODULE, inventory.id(), "取消订单影响库存",
                List.of("requirement:evidence"), 0.9, RelationStatus.EXTRACTED, null, null);
        store.replaceDraft(snapshot, List.of(feature, inventory), List.of(relation));

        com.example.requirementrag.retrieval.QdrantHybridStore qdrant =
                mock(com.example.requirementrag.retrieval.QdrantHybridStore.class);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        when(registry.resolveRequirementCollection("orders")).thenReturn("requirements_orders");
        ChunkRecord chunk = new ChunkRecord("chunk-1", "requirements", "2.0", "orders.md", "parent-1",
                "取消订单后影响库存。", "取消订单后影响库存。", "hash-1", 0, 0);
        when(qdrant.scrollVersion("requirements_orders", "requirements", "2.0"))
                .thenReturn(List.of(chunk));
        String evidenceId = RequirementGraphEvidence.id("orders", "2.0", chunk);
        Entity backedFeature = new Entity(feature.id(), snapshot.id(), feature.type(), feature.canonicalName(),
                feature.displayName(), feature.aliases(), feature.description(), List.of(evidenceId),
                List.of("parent-1"), List.of("hash-1"), feature.confidence(), EntityStatus.EXTRACTED);
        store.replaceDraft(snapshot, List.of(backedFeature, inventory),
                List.of(new Relation(relation.id(), snapshot.id(), feature.id(), relation.type(), inventory.id(),
                        relation.statement(), List.of(evidenceId), relation.confidence(), relation.status(), null, null)));

        RequirementGraphSearchService service = new RequirementGraphSearchService(store, qdrant, registry,
                new RequirementGraphProperties(true, true, true, tempDir.resolve("graph.db").toString(),
                        20, 30, 20_000, 2, 40, "model", "v1"));

        SearchResponse response = service.search(new SearchRequest(
                "orders", "requirements", "2.0", "取消订单", SearchMode.LOCAL, 10, 1));

        assertThat(response.relations()).singleElement().satisfies(item ->
                assertThat(item.type()).isEqualTo(RelationType.AFFECTS_MODULE));
        assertThat(response.entities()).extracting(Entity::displayName)
                .contains("取消订单", "库存");
        assertThat(response.evidence()).singleElement().satisfies(item -> {
            assertThat(item.evidenceId()).isEqualTo(evidenceId);
            assertThat(item.excerpt()).contains("取消订单");
        });
    }

    private Entity entity(String id, String snapshotId, String name, RequirementGraphModels.EntityType type) {
        return new Entity(id, snapshotId, type, name, name, List.of(), "",
                List.of("requirement:evidence"), List.of("parent-1"), List.of("hash-1"), 0.8,
                EntityStatus.EXTRACTED);
    }
}
