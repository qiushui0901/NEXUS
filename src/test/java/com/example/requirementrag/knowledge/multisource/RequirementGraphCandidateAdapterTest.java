package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ClaimStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Entity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.EntityStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.EntityType;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphProperties;
import com.example.requirementrag.requirement.graph.SQLiteRequirementGraphStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementGraphCandidateAdapterTest {
    @TempDir Path tempDir;

    @Test
    void loadsPublishedGraphEntitiesAsRequirementClaims() {
        RequirementGraphProperties properties = new RequirementGraphProperties(true, true, true,
                tempDir.resolve("graph.db").toString(), 20, 30, 20_000, 2, 40, "model", "v1");
        SQLiteRequirementGraphStore graphStore = new SQLiteRequirementGraphStore(new ObjectMapper(), properties);
        Instant now = Instant.now();
        GraphSnapshot snapshot = new GraphSnapshot("reqgraph:multi", "orders", "requirements", "2.0",
                "source", "model", "v1", SnapshotStatus.DRAFT, 1, 0, now, now, null);
        Entity entity = new Entity("entity:multi", snapshot.id(), EntityType.FEATURE,
                "cancel", "取消订单", List.of(), "订单取消功能", List.of("evidence:req-1"), List.of(), List.of("hash"),
                0.9, EntityStatus.EXTRACTED, ClaimStatus.VERIFIED, "reviewer", "ctx", "w", "w",
                List.of(), List.of(), "reviewer", now, "verified");
        graphStore.saveSnapshot(snapshot);
        graphStore.saveDraftSnapshot(snapshot, List.of(entity), List.of(),
                List.of(new com.example.requirementrag.requirement.graph.RequirementGraphModels.Evidence(
                        "evidence:req-1", "orders.md", "parent", 0, "2.0", "取消订单", "hash",
                        "订单", "取消订单", 0, 4,
                        com.example.requirementrag.requirement.graph.RequirementGraphModels.EvidenceResolutionStatus.RESOLVED)),
                List.of(), List.of());
        graphStore.updateStatus(snapshot.id(), SnapshotStatus.PUBLISHED, null);

        RequirementGraphCandidateAdapter adapter = new RequirementGraphCandidateAdapter(graphStore);
        List<UnifiedKnowledgeClaim> claims = adapter.load("orders", "2.0", "");

        assertThat(claims).isNotEmpty();
        assertThat(claims).allMatch(claim -> claim.sourceType().name().equals("REQUIREMENT"));
        assertThat(claims).anyMatch(claim -> "取消订单".equals(claim.subject()));
        assertThat(claims).anyMatch(claim -> "evidence:req-1".equals(claim.evidenceLocation()));
    }

    @Test
    void rejectedEntitiesAreExcludedFromNormativeClaims() {
        RequirementGraphProperties properties = new RequirementGraphProperties(true, true, true,
                tempDir.resolve("graph-rejected.db").toString(), 20, 30, 20_000, 2, 40, "model", "v1");
        SQLiteRequirementGraphStore graphStore = new SQLiteRequirementGraphStore(new ObjectMapper(), properties);
        Instant now = Instant.now();
        GraphSnapshot snapshot = new GraphSnapshot("reqgraph:rejected", "orders", "requirements", "2.0",
                "source", "model", "v1", SnapshotStatus.DRAFT, 1, 0, now, now, null);
        Entity entity = new Entity("entity:rejected", snapshot.id(), EntityType.FEATURE,
                "cancel", "取消订单", List.of(), "被拒绝的需求", List.of(), List.of(), List.of("hash"),
                0.9, EntityStatus.EXTRACTED, ClaimStatus.REJECTED, "reviewer", "ctx", "w", "w",
                List.of(), List.of(), "reviewer", now, "rejected");
        graphStore.saveSnapshot(snapshot);
        graphStore.saveDraftSnapshot(snapshot, List.of(entity), List.of(), List.of(), List.of(), List.of());
        graphStore.updateStatus(snapshot.id(), SnapshotStatus.PUBLISHED, null);

        RequirementGraphCandidateAdapter adapter = new RequirementGraphCandidateAdapter(graphStore);
        assertThat(adapter.load("orders", "2.0", "")).isEmpty();
    }
}