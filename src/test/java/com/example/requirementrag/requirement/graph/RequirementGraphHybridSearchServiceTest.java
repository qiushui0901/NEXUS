package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.model.ScoredChunk;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Entity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.EntityStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.QueryPlan;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Relation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationType;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchMode;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchRequest;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchResponse;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.retrieval.EmbeddingBatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequirementGraphHybridSearchServiceTest {
    @TempDir Path tempDir;

    @Test
    void localSearchReturnsOnlyVerifiedClaimsByDefaultAndLegacyModeCanPreview() {
        RequirementGraphProperties properties = new RequirementGraphProperties(true, true, true,
                tempDir.resolve("graph.db").toString(), 20, 30, 20_000, 2, 40, "model", "v1");
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(), properties);
        Instant now = Instant.now();
        GraphSnapshot snapshot = new GraphSnapshot("reqgraph:hybrid", "orders", "requirements", "2.0", "source",
                "model", "v1", SnapshotStatus.DRAFT, 2, 1, now, now, null);
        Entity verified = new Entity("entity:verified", snapshot.id(), RequirementGraphModels.EntityType.FEATURE,
                "cancel", "取消订单", List.of(), "", List.of(), List.of(), List.of(), 0.9, EntityStatus.EXTRACTED,
                RequirementGraphModels.ClaimStatus.VERIFIED, "reviewer", "context", "window", "window",
                List.of(), List.of(), "reviewer", now, "verified");
        Entity extracted = new Entity("entity:extracted", snapshot.id(), RequirementGraphModels.EntityType.MODULE,
                "inventory", "库存", List.of(), "", List.of(), List.of(), List.of(), 0.8, EntityStatus.EXTRACTED);
        store.saveSnapshot(snapshot);
        store.replaceDraft(snapshot, List.of(verified, extracted), List.of());
        store.updateStatus(snapshot.id(), SnapshotStatus.PUBLISHED, null);
        RequirementGraphSearchService legacy = new RequirementGraphSearchService(store, mock(QdrantHybridStore.class),
                mock(com.example.requirementrag.config.ProjectRegistry.class), properties);
        RequirementGraphHybridSearchService hybrid = new RequirementGraphHybridSearchService(store,
                mock(QdrantHybridStore.class), mock(ObjectProvider.class), properties, legacy);

        var response = hybrid.search(new SearchRequest("orders", "requirements", "2.0", "取消订单", SearchMode.HYBRID, 10, 1,
                List.of(RequirementGraphModels.ClaimStatus.VERIFIED), false, 0));

        assertThat(response.entities()).extracting(Entity::id).containsExactly("entity:verified");
        assertThat(response.truncated()).isFalse();
    }

    @Test
    void mixSearchReturnsFusedChannelsWithSourceChunksPathsAndChannelScores() {
        RequirementGraphProperties properties = new RequirementGraphProperties(true, true, true,
                tempDir.resolve("graph-mix.db").toString(), 20, 30, 20_000, 2, 40, "model", "v1");
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(), properties);
        QdrantHybridStore qdrant = mock(QdrantHybridStore.class);
        Instant now = Instant.now();
        GraphSnapshot snapshot = new GraphSnapshot("reqgraph:mix", "orders", "requirements", "2.0", "source",
                "model", "v1", SnapshotStatus.DRAFT, 2, 1, now, now, null);
        Entity cancel = new Entity("entity:cancel", snapshot.id(), RequirementGraphModels.EntityType.FEATURE,
                "cancel", "取消订单", List.of(), "用户发起取消", List.of("requirement:abc"), List.of(), List.of("hash-abc"),
                0.9, EntityStatus.EXTRACTED, RequirementGraphModels.ClaimStatus.VERIFIED, "reviewer", "ctx", "w1", "w1",
                List.of(), List.of(), "reviewer", now, "verified");
        Entity inventory = new Entity("entity:inventory", snapshot.id(), RequirementGraphModels.EntityType.MODULE,
                "inventory", "库存", List.of(), "库存模块", List.of("requirement:def"), List.of(), List.of("hash-def"),
                0.8, EntityStatus.EXTRACTED, RequirementGraphModels.ClaimStatus.VERIFIED, "reviewer", "ctx", "w1", "w1",
                List.of(), List.of(), "reviewer", now, "verified");
        Relation relation = new Relation("rel:cancel->inventory", snapshot.id(), "entity:cancel",
                RelationType.AFFECTS_MODULE, "entity:inventory", "取消订单影响库存扣减",
                List.of("requirement:def"), 0.85, RelationStatus.EXTRACTED, "reviewer", now,
                RequirementGraphModels.ClaimStatus.VERIFIED, "", "", List.of("取消订单影响库存扣减"), List.of(), List.of(), null);
        store.saveSnapshot(snapshot);
        store.replaceDraft(snapshot, List.of(cancel, inventory), List.of(relation));
        store.saveEvidence(snapshot.id(), List.of(
                new RequirementGraphModels.Evidence("requirement:abc", "orders.md", "p1", 0, "2.0", "取消订单", "hash-abc",
                        "订单", "用户发起取消订单", 0, 6, RequirementGraphModels.EvidenceResolutionStatus.RESOLVED),
                new RequirementGraphModels.Evidence("requirement:def", "orders.md", "p2", 1, "2.0", "库存扣减", "hash-def",
                        "库存", "取消订单后扣减库存", 0, 6, RequirementGraphModels.EvidenceResolutionStatus.RESOLVED)));
        store.updateStatus(snapshot.id(), SnapshotStatus.PUBLISHED, null);

        ChunkRecord chunk = new ChunkRecord("chunk:1", "requirements", "2.0", "orders.md", "p1",
                "取消订单相关需求说明", "用户发起取消订单", "hash-abc", 0, 0);
        when(qdrant.hybridSearchWithScores(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(new ScoredChunk(chunk, 0.8)));

        RequirementGraphSearchService legacy = new RequirementGraphSearchService(store, qdrant,
                mock(com.example.requirementrag.config.ProjectRegistry.class), properties);
        RequirementGraphHybridSearchService hybrid = new RequirementGraphHybridSearchService(store,
                qdrant, mock(ObjectProvider.class), properties, legacy);

        QueryPlan plan = new QueryPlan(SearchMode.MIX, List.of("取消订单"), List.of("影响"), List.of("取消订单"),
                2, 20, 20, 20, Set.of(RequirementGraphModels.ClaimStatus.VERIFIED));
        var response = hybrid.search(new SearchRequest("orders", "requirements", "2.0", "取消订单", SearchMode.MIX, 10, 2,
                List.of(RequirementGraphModels.ClaimStatus.VERIFIED), false, 0), plan);

        assertThat(response.entities()).extracting(Entity::id).contains("entity:cancel");
        assertThat(response.relations()).extracting(Relation::id).contains("rel:cancel->inventory");
        assertThat(response.sourceChunks()).extracting(ChunkRecord::id).contains("chunk:1");
        assertThat(response.paths()).isNotEmpty();
        assertThat(response.paths().get(0).entityIds()).contains("entity:cancel", "entity:inventory");
        assertThat(response.evidence()).isNotEmpty();
        assertThat(response.channelScores()).containsKeys("text", "entity", "relation", "path", "evidence", "freshness");
        assertThat(response.plan()).isSameAs(plan);
    }

    @Test
    void naiveSearchReturnsOnlyRawTextChunksWithoutGraphClaims() {
        RequirementGraphProperties properties = new RequirementGraphProperties(true, true, true,
                tempDir.resolve("graph-naive.db").toString(), 20, 30, 20_000, 2, 40, "model", "v1");
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(), properties);
        QdrantHybridStore qdrant = mock(QdrantHybridStore.class);
        ChunkRecord chunk = new ChunkRecord("chunk:naive", "requirements", "2.0", "orders.md", "p1",
                "原始需求文本", "取消订单", "hash-naive", 0, 0);
        when(qdrant.hybridSearchWithScores(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(new ScoredChunk(chunk, 0.8)));
        RequirementGraphSearchService legacy = new RequirementGraphSearchService(store, qdrant,
                mock(com.example.requirementrag.config.ProjectRegistry.class), properties);
        RequirementGraphHybridSearchService hybrid = new RequirementGraphHybridSearchService(store,
                qdrant, mock(ObjectProvider.class), properties, legacy);

        var response = hybrid.search(new SearchRequest("orders", "requirements", "2.0", "取消订单", SearchMode.NAIVE, 10, 0,
                List.of(RequirementGraphModels.ClaimStatus.VERIFIED), false, 0));

        assertThat(response.sourceChunks()).extracting(ChunkRecord::id).containsExactly("chunk:naive");
        assertThat(response.entities()).isEmpty();
        assertThat(response.relations()).isEmpty();
        assertThat(response.total()).isEqualTo(1);
    }

    @Test
    void textHitFromEvidenceParentChangesEntityOrdering() {
        RequirementGraphProperties properties = new RequirementGraphProperties(true, true, true,
                tempDir.resolve("graph-mix-order.db").toString(), 20, 30, 20_000, 2, 40, "model", "v1");
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(), properties);
        QdrantHybridStore qdrant = mock(QdrantHybridStore.class);
        Instant now = Instant.now();
        GraphSnapshot snapshot = new GraphSnapshot("reqgraph:order", "orders", "requirements", "2.0", "source",
                "model", "v1", SnapshotStatus.DRAFT, 2, 0, now, now, null);
        Entity first = new Entity("entity:order-a", snapshot.id(), RequirementGraphModels.EntityType.FEATURE,
                "cancel-a", "取消订单", List.of(), "第一个取消订单实体", List.of("requirement:abc"), List.of(), List.of("hash-abc"),
                0.8, EntityStatus.EXTRACTED, RequirementGraphModels.ClaimStatus.VERIFIED, "reviewer", "ctx", "w", "w",
                List.of(), List.of(), "reviewer", now, "verified");
        Entity second = new Entity("entity:order-b", snapshot.id(), RequirementGraphModels.EntityType.FEATURE,
                "cancel-b", "取消订单", List.of(), "第二个取消订单实体", List.of("requirement:def"), List.of(), List.of("hash-def"),
                0.8, EntityStatus.EXTRACTED, RequirementGraphModels.ClaimStatus.VERIFIED, "reviewer", "ctx", "w", "w",
                List.of(), List.of(), "reviewer", now, "verified");
        store.saveSnapshot(snapshot);
        store.saveDraftSnapshot(snapshot, List.of(first, second), List.of(), List.of(
                new RequirementGraphModels.Evidence("requirement:abc", "orders.md", "parent-1", 0, "2.0", "取消订单", "hash-abc",
                        "订单", "用户发起取消订单", 0, 6, RequirementGraphModels.EvidenceResolutionStatus.RESOLVED),
                new RequirementGraphModels.Evidence("requirement:def", "orders.md", "parent-2", 0, "2.0", "取消订单", "hash-def",
                        "订单", "用户再次取消订单", 0, 6, RequirementGraphModels.EvidenceResolutionStatus.RESOLVED)),
                List.of(), List.of());
        store.updateStatus(snapshot.id(), SnapshotStatus.PUBLISHED, null);
        ChunkRecord chunk = new ChunkRecord("chunk:hot", "requirements", "2.0", "orders.md", "parent-1",
                "取消订单需求说明", "用户发起取消订单", "hash-abc", 0, 0);
        when(qdrant.hybridSearchWithScores(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(new ScoredChunk(chunk, 0.9)));

        RequirementGraphSearchService legacy = new RequirementGraphSearchService(store, qdrant,
                mock(com.example.requirementrag.config.ProjectRegistry.class), properties);
        RequirementGraphHybridSearchService hybrid = new RequirementGraphHybridSearchService(store,
                qdrant, mock(ObjectProvider.class), properties, legacy);
        var response = hybrid.search(new SearchRequest("orders", "requirements", "2.0", "取消订单", SearchMode.MIX, 10, 0,
                List.of(RequirementGraphModels.ClaimStatus.VERIFIED), false, 0), null);

        assertThat(response.entities()).extracting(Entity::id).first().isEqualTo("entity:order-a");
        assertThat(response.sourceChunks()).extracting(ChunkRecord::id).contains("chunk:hot");
    }

    @Test
    void mixSearchReportsTextChannelUnavailableWarning() {
        RequirementGraphProperties properties = new RequirementGraphProperties(true, true, true,
                tempDir.resolve("graph-mix-unavailable.db").toString(), 20, 30, 20_000, 2, 40, "model", "v1");
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(), properties);
        QdrantHybridStore qdrant = mock(QdrantHybridStore.class);
        Instant now = Instant.now();
        GraphSnapshot snapshot = new GraphSnapshot("reqgraph:unavailable", "orders", "requirements", "2.0",
                "source", "model", "v1", SnapshotStatus.DRAFT, 1, 0, now, now, null);
        Entity verified = new Entity("entity:unavail", snapshot.id(), RequirementGraphModels.EntityType.FEATURE,
                "cancel", "取消订单", List.of(), "", List.of(), List.of(), List.of(), 0.9, EntityStatus.EXTRACTED,
                RequirementGraphModels.ClaimStatus.VERIFIED, "reviewer", "ctx", "w", "w",
                List.of(), List.of(), "reviewer", now, "verified");
        store.saveSnapshot(snapshot);
        store.replaceDraft(snapshot, List.of(verified), List.of());
        store.updateStatus(snapshot.id(), SnapshotStatus.PUBLISHED, null);
        when(qdrant.hybridSearchWithScores(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("connection refused"));

        RequirementGraphSearchService legacy = new RequirementGraphSearchService(store, qdrant,
                mock(com.example.requirementrag.config.ProjectRegistry.class), properties);
        RequirementGraphHybridSearchService hybrid = new RequirementGraphHybridSearchService(store,
                qdrant, mock(ObjectProvider.class), properties, legacy);
        var response = hybrid.search(new SearchRequest("orders", "requirements", "2.0", "取消订单", SearchMode.MIX, 10, 0,
                List.of(RequirementGraphModels.ClaimStatus.VERIFIED), false, 0), null);

        assertThat(response.entities()).extracting(Entity::id).contains("entity:unavail");
        assertThat(response.warnings()).extracting(RagWarning::code)
                .contains("GRAPH_TEXT_RETRIEVAL_UNAVAILABLE");
    }

    @Test
    void mixSearchPaginatesChunksAndPathsWithoutRepeats() {
        RequirementGraphProperties properties = new RequirementGraphProperties(true, true, true,
                tempDir.resolve("graph-mix-page.db").toString(), 20, 30, 20_000, 2, 40, "model", "v1");
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(), properties);
        QdrantHybridStore qdrant = mock(QdrantHybridStore.class);
        Instant now = Instant.now();
        GraphSnapshot snapshot = new GraphSnapshot("reqgraph:page", "orders", "requirements", "2.0",
                "source", "model", "v1", SnapshotStatus.DRAFT, 3, 2, now, now, null);
        Entity cancel = new Entity("entity:cancel", snapshot.id(), RequirementGraphModels.EntityType.FEATURE,
                "cancel", "取消订单", List.of(), "", List.of(), List.of(), List.of(), 0.9, EntityStatus.EXTRACTED,
                RequirementGraphModels.ClaimStatus.VERIFIED, "reviewer", "ctx", "w", "w",
                List.of(), List.of(), "reviewer", now, "verified");
        Entity inventory = new Entity("entity:inventory", snapshot.id(), RequirementGraphModels.EntityType.MODULE,
                "inventory", "库存", List.of(), "", List.of(), List.of(), List.of(), 0.8, EntityStatus.EXTRACTED,
                RequirementGraphModels.ClaimStatus.VERIFIED, "reviewer", "ctx", "w", "w",
                List.of(), List.of(), "reviewer", now, "verified");
        Entity warehouse = new Entity("entity:warehouse", snapshot.id(), RequirementGraphModels.EntityType.MODULE,
                "warehouse", "仓库", List.of(), "", List.of(), List.of(), List.of(), 0.8, EntityStatus.EXTRACTED,
                RequirementGraphModels.ClaimStatus.VERIFIED, "reviewer", "ctx", "w", "w",
                List.of(), List.of(), "reviewer", now, "verified");
        List<Relation> relations = List.of(
                new Relation("rel:one", snapshot.id(), cancel.id(), RelationType.AFFECTS_MODULE, inventory.id(),
                        "取消订单影响库存", List.of(), 0.8, RelationStatus.EXTRACTED, "reviewer", now,
                        RequirementGraphModels.ClaimStatus.VERIFIED, "", "", List.of(), List.of(), List.of(), null),
                new Relation("rel:two", snapshot.id(), cancel.id(), RelationType.AFFECTS_MODULE, warehouse.id(),
                        "取消订单影响仓库", List.of(), 0.8, RelationStatus.EXTRACTED, "reviewer", now,
                        RequirementGraphModels.ClaimStatus.VERIFIED, "", "", List.of(), List.of(), List.of(), null));
        store.saveSnapshot(snapshot);
        store.replaceDraft(snapshot, List.of(cancel, inventory, warehouse), relations);
        store.updateStatus(snapshot.id(), SnapshotStatus.PUBLISHED, null);
        assertThat(store.entities(snapshot.id(), "取消订单", null, 10)).isNotEmpty();
        assertThat(store.allRelations(snapshot.id(), 10)).hasSize(2);
        assertThat(store.allRelations(snapshot.id(), 10))
                .allMatch(relation -> relation.claimStatus() == RequirementGraphModels.ClaimStatus.VERIFIED);
        ChunkRecord chunkOne = new ChunkRecord("chunk:page-1", "requirements", "2.0", "orders.md", "p1",
                "第一块", "取消订单", "hash-1", 0, 0);
        ChunkRecord chunkTwo = new ChunkRecord("chunk:page-2", "requirements", "2.0", "orders.md", "p2",
                "第二块", "取消订单", "hash-2", 1, 0);
        when(qdrant.hybridSearchWithScores(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(new ScoredChunk(chunkOne, 0.9), new ScoredChunk(chunkTwo, 0.8)));

        RequirementGraphSearchService legacy = new RequirementGraphSearchService(store, qdrant,
                mock(com.example.requirementrag.config.ProjectRegistry.class), properties);
        RequirementGraphHybridSearchService hybrid = new RequirementGraphHybridSearchService(store,
                qdrant, mock(ObjectProvider.class), properties, legacy);
        var page0 = hybrid.search(new SearchRequest("orders", "requirements", "2.0", "取消订单", SearchMode.MIX, 1, 1,
                List.of(RequirementGraphModels.ClaimStatus.VERIFIED), false, 0), null);
        var page1 = hybrid.search(new SearchRequest("orders", "requirements", "2.0", "取消订单", SearchMode.MIX, 1, 1,
                List.of(RequirementGraphModels.ClaimStatus.VERIFIED), false, 1), null);

        assertThat(page0.total()).isEqualTo(page1.total());
        assertThat(page0.total()).isGreaterThan(1);
        assertThat(unifiedKeys(page1)).doesNotContainAnyElementsOf(unifiedKeys(page0));
    }

    /** 统一分页下每个候选使用跨通道稳定键，保证不同页之间不重复。 */
    private java.util.List<String> unifiedKeys(SearchResponse response) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        response.sourceChunks().forEach(chunk -> keys.add("TEXT:" + chunk.id()));
        response.paths().forEach(path -> keys.add("PATH:" + String.join(">", path.entityIds())));
        response.entities().forEach(entity -> keys.add("ENTITY:" + entity.id()));
        response.relations().forEach(relation -> keys.add("RELATION:" + relation.id()));
        response.evidence().forEach(evidence -> keys.add("EVIDENCE:" + evidence.evidenceId()));
        return keys;
    }
}
