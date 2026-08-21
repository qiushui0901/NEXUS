package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.requirement.graph.RequirementGraphModels.ClaimStatus;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class SQLiteRequirementGraphStorePublicationTest {
    @TempDir Path tempDir;

    @Test
    void publicationRequiresVerifiedClaimsAndResolvedEvidence() {
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(),
                new RequirementGraphProperties(true, true, true, tempDir.resolve("graph.db").toString(),
                        20, 30, 20_000, 2, 40, "model", "v1"));
        Instant now = Instant.now();
        GraphSnapshot snapshot = new GraphSnapshot("reqgraph:publish", "orders", "requirements", "2.0",
                "source", "model", "v1", SnapshotStatus.REVIEW_REQUIRED, 1, 0, now, now, null,
                2, "v1", 1.0, 1, 1, 0, 0, "build", null, null, null);
        Entity entity = new Entity("entity:one", snapshot.id(), RequirementGraphModels.EntityType.FEATURE,
                "cancel", "取消订单", List.of(), "", List.of("evidence:one"), List.of(), List.of("hash"),
                0.9, EntityStatus.EXTRACTED);
        store.saveSnapshot(snapshot);
        store.replaceDraft(snapshot, List.of(entity), List.of());
        store.saveEvidence(snapshot.id(), List.of(new RequirementGraphModels.Evidence("evidence:one", "orders.md",
                "parent", 0, "2.0", "取消订单", "hash", "订单", "取消订单", 0, 4,
                RequirementGraphModels.EvidenceResolutionStatus.RESOLVED)));

        assertThatThrownBy(() -> store.publishSnapshot(snapshot.id(), "reviewer", "publish"))
                .isInstanceOf(RequirementGraphException.class)
                .hasMessageContaining("未审核");
        store.reviewEntity(entity.id(), ClaimStatus.VERIFIED, "reviewer", "verified");
        assertThat(store.publishSnapshot(snapshot.id(), "reviewer", "publish").status())
                .isEqualTo(SnapshotStatus.PUBLISHED);
    }

    @Test
    void publicationBlocksVerifiedClaimWithMissingEvidence() {
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(),
                new RequirementGraphProperties(true, true, true, tempDir.resolve("graph-missing.db").toString(),
                        20, 30, 20_000, 2, 40, "model", "v1"));
        Instant now = Instant.now();
        GraphSnapshot snapshot = new GraphSnapshot("reqgraph:missing-evidence", "orders", "requirements", "2.0",
                "source", "model", "v1", SnapshotStatus.REVIEW_REQUIRED, 1, 0, now, now, null,
                2, "v1", 1.0, 1, 1, 0, 0, "build", null, null, null);
        Entity entity = new Entity("entity:missing", snapshot.id(), RequirementGraphModels.EntityType.FEATURE,
                "cancel", "取消订单", List.of(), "", List.of("evidence:does-not-exist"), List.of(), List.of("hash"),
                0.9, EntityStatus.EXTRACTED);
        store.saveSnapshot(snapshot);
        store.replaceDraft(snapshot, List.of(entity), List.of());
        store.reviewEntity(entity.id(), ClaimStatus.VERIFIED, "reviewer", "verified");

        assertThatThrownBy(() -> store.publishSnapshot(snapshot.id(), "reviewer", "publish"))
                .isInstanceOf(RequirementGraphException.class)
                .hasMessageContaining("GRAPH_EVIDENCE_MISSING");
    }

    @Test
    void claimEvidenceAssociationIsNormalizedAndCleanedUpWithDraft() {
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(),
                new RequirementGraphProperties(true, true, true, tempDir.resolve("graph-claim-evidence.db").toString(),
                        20, 30, 20_000, 2, 40, "model", "v1"));
        Instant now = Instant.now();
        GraphSnapshot snapshot = new GraphSnapshot("reqgraph:claim-evidence", "orders", "requirements", "2.0",
                "source", "model", "v1", SnapshotStatus.DRAFT, 1, 0, now, now, null,
                2, "v1", 1.0, 1, 1, 0, 0, "build", null, null, null);
        Entity entity = new Entity("entity:ce", snapshot.id(), RequirementGraphModels.EntityType.FEATURE,
                "cancel", "取消订单", List.of(), "", List.of("evidence:ce-one"), List.of(), List.of("hash"),
                0.9, EntityStatus.EXTRACTED);
        store.saveSnapshot(snapshot);
        store.saveDraftSnapshot(snapshot, List.of(entity), List.of(),
                List.of(new RequirementGraphModels.Evidence("evidence:ce-one", "orders.md",
                        "parent", 0, "2.0", "取消订单", "hash", "订单", "取消订单", 0, 4,
                        RequirementGraphModels.EvidenceResolutionStatus.RESOLVED)),
                List.of(), List.of());

        assertThat(store.claimEvidence(snapshot.id(), entity.id()))
                .extracting(RequirementGraphModels.ClaimEvidence::evidenceId)
                .containsExactly("evidence:ce-one");

        // 重建草稿会清空并重建规范化关联；空草稿则不残留任何关联。
        store.saveDraftSnapshot(snapshot, List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(store.claimEvidence(snapshot.id(), entity.id())).isEmpty();
    }
}
