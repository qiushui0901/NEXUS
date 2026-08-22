package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.requirement.graph.RequirementGraphModels.Entity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.EntityStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Relation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationType;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RequirementGraphWindowView;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.WindowStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void restartMarksRunningJobFailedAndBackfillsSnapshotId() {
        SQLiteRequirementGraphStore store = store("graph-restart.db");
        Instant now = Instant.now();
        GraphSnapshot snapshot = new GraphSnapshot("reqgraph:interrupted", "orders", "requirements", "2.0",
                "source-hash", "model", "v1", SnapshotStatus.BUILDING, 0, 0, now, now, null,
                2, "v1", 1.0, 0, 0, 0, 0, "graph-job:abc", null, null, null);
        store.saveSnapshot(snapshot);
        store.saveBuildJob(new SQLiteRequirementGraphStore.StoredBuildJob(
                new RequirementGraphModels.BuildJob("graph-job:abc", null, "orders", "requirements", "2.0",
                        RequirementGraphModels.BuildJobState.RUNNING, 0, 0, null, null, now, now, null),
                "{}", false, null));

        store.markInterruptedBuildJobs();

        var restored = store.loadBuildJob("graph-job:abc").orElseThrow();
        assertThat(restored.job().state()).isEqualTo(RequirementGraphModels.BuildJobState.FAILED);
        assertThat(restored.job().snapshotId()).isEqualTo("reqgraph:interrupted");
        assertThat(restored.job().errorCode()).isEqualTo("GRAPH_JOB_INTERRUPTED");
    }

    @Test
    void twoSnapshotsWithIdenticalChunksKeepIndependentWindows() {
        SQLiteRequirementGraphStore store = store("graph-windows.db");
        Instant now = Instant.now();
        GraphSnapshot first = new GraphSnapshot("reqgraph:win-a", "orders", "requirements", "2.0",
                "source-hash-1", "model", "v1", SnapshotStatus.BUILDING, 0, 0, now, now, null);
        GraphSnapshot second = new GraphSnapshot("reqgraph:win-b", "orders", "requirements", "2.0",
                "source-hash-2", "model", "v1", SnapshotStatus.BUILDING, 0, 0, now, now, null);
        store.saveSnapshot(first);
        store.saveSnapshot(second);
        RequirementGraphWindowView window = new RequirementGraphWindowView("window:same", first.id(), "orders.md",
                "parent", "", "", 0, 0, 100, "hash", WindowStatus.PENDING, 0, null, null, null, null);

        store.saveWindows(first.id(), List.of(window));
        store.saveWindows(second.id(), List.of(new RequirementGraphWindowView(window.id(), second.id(), window.filename(),
                window.parentId(), window.sectionPath(), window.heading(), window.windowIndex(), window.startOffset(),
                window.endOffset(), window.contentHash(), window.status(), window.attemptCount(), window.lastErrorCode(),
                window.startedAt(), window.completedAt(), window.continuationOf())));

        assertThat(store.windows(first.id())).extracting(RequirementGraphWindowView::id).containsExactly("window:same");
        assertThat(store.windows(second.id())).extracting(RequirementGraphWindowView::id).containsExactly("window:same");
    }

    @Test
    void publishedAndNewSnapshotKeepIndependentEvidence() {
        SQLiteRequirementGraphStore store = store("graph-evidence.db");
        Instant now = Instant.now();
        GraphSnapshot published = new GraphSnapshot("reqgraph:ev-pub", "orders", "requirements", "2.0",
                "source-hash-1", "model", "v1", SnapshotStatus.REVIEW_REQUIRED, 1, 0, now, now, null);
        GraphSnapshot draft = new GraphSnapshot("reqgraph:ev-draft", "orders", "requirements", "2.0",
                "source-hash-2", "model", "v1", SnapshotStatus.DRAFT, 1, 0, now, now, null);
        store.saveSnapshot(published);
        store.saveSnapshot(draft);
        RequirementGraphModels.Evidence evidence = new RequirementGraphModels.Evidence("requirement:shared", "orders.md",
                "parent", 0, "2.0", "取消订单", "hash", "订单", "取消订单", 0, 4,
                RequirementGraphModels.EvidenceResolutionStatus.RESOLVED);
        Entity entity = new Entity("entity:ev-pub", published.id(), RequirementGraphModels.EntityType.FEATURE,
                "cancel", "取消订单", List.of(), "", List.of(evidence.evidenceId()), List.of(), List.of("hash"),
                0.9, EntityStatus.EXTRACTED);
        store.saveDraftSnapshot(published, List.of(entity), List.of(), List.of(evidence), List.of(), List.of());
        store.updateStatus(published.id(), SnapshotStatus.PUBLISHED, null);
        Entity draftEntity = new Entity("entity:ev-draft", draft.id(), RequirementGraphModels.EntityType.FEATURE,
                "cancel", "取消订单", List.of(), "", List.of(evidence.evidenceId()), List.of(), List.of("hash"),
                0.9, EntityStatus.EXTRACTED);
        store.saveDraftSnapshot(draft, List.of(draftEntity), List.of(), List.of(evidence), List.of(), List.of());

        assertThat(store.evidence(published.id(), java.util.Set.of(evidence.evidenceId())))
                .extracting(RequirementGraphModels.Evidence::evidenceId).containsExactly(evidence.evidenceId());
        assertThat(store.evidence(draft.id(), java.util.Set.of(evidence.evidenceId())))
                .extracting(RequirementGraphModels.Evidence::evidenceId).containsExactly(evidence.evidenceId());
    }

    @Test
    void deletingSnapshotCascadesWindowsEvidenceAndClaimEvidence() {
        SQLiteRequirementGraphStore store = store("graph-cascade.db");
        Instant now = Instant.now();
        GraphSnapshot snapshot = new GraphSnapshot("reqgraph:cascade", "orders", "requirements", "2.0",
                "source-hash", "model", "v1", SnapshotStatus.DRAFT, 1, 0, now, now, null);
        store.saveSnapshot(snapshot);
        RequirementGraphModels.Evidence evidence = new RequirementGraphModels.Evidence("requirement:ce", "orders.md",
                "parent", 0, "2.0", "取消订单", "hash", "订单", "取消订单", 0, 4,
                RequirementGraphModels.EvidenceResolutionStatus.RESOLVED);
        Entity entity = new Entity("entity:cascade", snapshot.id(), RequirementGraphModels.EntityType.FEATURE,
                "cancel", "取消订单", List.of(), "", List.of(evidence.evidenceId()), List.of(), List.of("hash"),
                0.9, EntityStatus.EXTRACTED);
        store.saveDraftSnapshot(snapshot, List.of(entity), List.of(), List.of(evidence), List.of(), List.of());
        store.saveWindows(snapshot.id(), List.of(new RequirementGraphWindowView("window:one", snapshot.id(), "orders.md",
                "parent", "", "", 0, 0, 100, "hash", WindowStatus.PENDING, 0, null, null, null, null)));

        store.deleteSnapshot(snapshot.id());

        assertThat(store.windows(snapshot.id())).isEmpty();
        assertThat(store.evidence(snapshot.id(), java.util.Set.of(evidence.evidenceId()))).isEmpty();
        assertThat(store.claimEvidence(snapshot.id(), entity.id())).isEmpty();
        assertThatThrownBy(() -> store.requireSnapshot(snapshot.id()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claimEvidenceForeignKeyRejectsDanglingEvidence() {
        SQLiteRequirementGraphStore store = store("graph-fk.db");
        Instant now = Instant.now();
        GraphSnapshot snapshot = new GraphSnapshot("reqgraph:fk", "orders", "requirements", "2.0",
                "source-hash", "model", "v1", SnapshotStatus.DRAFT, 1, 0, now, now, null);
        store.saveSnapshot(snapshot);
        RequirementGraphModels.Evidence evidence = new RequirementGraphModels.Evidence("requirement:present", "orders.md",
                "parent", 0, "2.0", "取消订单", "hash", "订单", "取消订单", 0, 4,
                RequirementGraphModels.EvidenceResolutionStatus.RESOLVED);
        Entity entity = new Entity("entity:fk", snapshot.id(), RequirementGraphModels.EntityType.FEATURE,
                "cancel", "取消订单", List.of(), "", List.of(evidence.evidenceId()), List.of(), List.of("hash"),
                0.9, EntityStatus.EXTRACTED);
        store.saveDraftSnapshot(snapshot, List.of(entity), List.of(), List.of(evidence), List.of(), List.of());

        assertThatThrownBy(() -> store.replaceClaimEvidence(snapshot.id(), entity.id(),
                List.of("requirement:not-saved"), 0.9))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void publishedSnapshotRejectsGraphWrites() {
        SQLiteRequirementGraphStore store = store("graph-immutable.db");
        Instant now = Instant.now();
        GraphSnapshot snapshot = new GraphSnapshot("reqgraph:immutable", "orders", "requirements", "2.0",
                "source-hash", "model", "v1", SnapshotStatus.PUBLISHED, 1, 0, now, now, now);
        store.saveSnapshot(snapshot);

        assertThatThrownBy(() -> store.saveDraftSnapshot(snapshot, List.of(), List.of(), List.of(), List.of(), List.of()))
                .isInstanceOf(RequirementGraphException.class)
                .satisfies(exception -> assertThat(((RequirementGraphException) exception).code())
                        .isEqualTo("GRAPH_SNAPSHOT_IMMUTABLE"));
        assertThatThrownBy(() -> store.replaceDraft(snapshot, List.of(), List.of()))
                .isInstanceOf(RequirementGraphException.class)
                .satisfies(exception -> assertThat(((RequirementGraphException) exception).code())
                        .isEqualTo("GRAPH_SNAPSHOT_IMMUTABLE"));
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
