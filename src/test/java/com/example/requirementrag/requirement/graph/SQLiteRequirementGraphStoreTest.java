package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.requirement.graph.RequirementGraphModels.Entity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.EntityStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Relation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationType;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SQLiteRequirementGraphStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void replacesDraftAtomicallyAndReadsVersionScopedGraph() {
        SQLiteRequirementGraphStore store = store("graph.db");
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        GraphSnapshot snapshot = new GraphSnapshot("reqgraph:one", "orders", "requirements", "2.0",
                "source-hash", "model", "v1", SnapshotStatus.BUILDING, 0, 0, now, now, null);
        store.saveSnapshot(snapshot);
        Entity source = new Entity("entity:source", snapshot.id(),
                RequirementGraphModels.EntityType.FEATURE, "cancelorder", "取消订单", List.of("撤单"),
                "订单取消功能", List.of("requirement:e1"), List.of("parent-1"), List.of("hash-1"),
                0.9, EntityStatus.EXTRACTED);
        Entity target = new Entity("entity:target", snapshot.id(),
                RequirementGraphModels.EntityType.MODULE, "inventory", "库存", List.of(),
                "库存模块", List.of("requirement:e1"), List.of("parent-1"), List.of("hash-1"),
                0.8, EntityStatus.EXTRACTED);
        Relation relation = new Relation("relation:one", snapshot.id(), source.id(),
                RelationType.AFFECTS_MODULE, target.id(), "取消订单影响库存",
                List.of("requirement:e1"), 0.8, RelationStatus.EXTRACTED, null, null);

        store.replaceDraft(new GraphSnapshot(snapshot.id(), snapshot.businessProjectId(), snapshot.documentId(),
                snapshot.requirementVersion(), snapshot.sourceRevision(), snapshot.extractionModel(),
                snapshot.promptVersion(), SnapshotStatus.REVIEW_REQUIRED, 2, 1, now, now, null),
                List.of(source, target), List.of(relation));

        GraphSnapshot loaded = store.requireSnapshot(snapshot.id());
        assertThat(loaded.status()).isEqualTo(SnapshotStatus.REVIEW_REQUIRED);
        assertThat(loaded.entityCount()).isEqualTo(2);
        assertThat(loaded.relationCount()).isEqualTo(1);
        assertThat(store.entities(snapshot.id(), "取消", null, 10)).extracting(Entity::id)
                .containsExactly("entity:source");
        assertThat(store.allRelations(snapshot.id(), 10)).extracting(Relation::type)
                .containsExactly(RelationType.AFFECTS_MODULE);
        assertThat(store.findLatest("orders", "requirements", "2.0")).contains(loaded);
    }

    @Test
    void deletingAndRebuildingSnapshotDoesNotLeaveOldEdges() {
        SQLiteRequirementGraphStore store = store("graph.db");
        Instant now = Instant.now();
        GraphSnapshot snapshot = new GraphSnapshot("reqgraph:two", "orders", "requirements", "2.0",
                "source-hash", "model", "v1", SnapshotStatus.BUILDING, 0, 0, now, now, null);
        store.saveSnapshot(snapshot);
        Entity first = entity("entity:first", snapshot.id());
        Entity second = entity("entity:second", snapshot.id());
        store.replaceDraft(new GraphSnapshot(snapshot.id(), "orders", "requirements", "2.0", "source-hash",
                "model", "v1", SnapshotStatus.REVIEW_REQUIRED, 2, 1, now, now, null),
                List.of(first, second), List.of(new Relation("relation:old", snapshot.id(), first.id(),
                        RelationType.DEPENDS_ON, second.id(), "旧关系", List.of("requirement:old"), 0.5,
                        RelationStatus.EXTRACTED, null, null)));
        store.replaceDraft(new GraphSnapshot(snapshot.id(), "orders", "requirements", "2.0", "source-hash-2",
                "model", "v1", SnapshotStatus.REVIEW_REQUIRED, 1, 0, now, now, null),
                List.of(first), List.of());

        assertThat(store.allEntities(snapshot.id(), 10)).extracting(Entity::id).containsExactly(first.id());
        assertThat(store.allRelations(snapshot.id(), 10)).isEmpty();
    }

    private Entity entity(String id, String snapshotId) {
        return new Entity(id, snapshotId, RequirementGraphModels.EntityType.MODULE,
                id, id, List.of(), "", List.of("requirement:e"), List.of("parent"), List.of("hash"),
                0.5, EntityStatus.EXTRACTED);
    }

    private SQLiteRequirementGraphStore store(String name) {
        return new SQLiteRequirementGraphStore(new ObjectMapper(), new RequirementGraphProperties(
                true, true, true, tempDir.resolve(name).toString(), 20, 30, 20_000, 2, 40, "model", "v1"));
    }
}
