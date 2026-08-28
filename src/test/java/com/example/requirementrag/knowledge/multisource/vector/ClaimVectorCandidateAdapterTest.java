package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.knowledge.multisource.MultiSourceCandidateAdapter.CandidateLoad;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels;
import com.example.requirementrag.knowledge.multisource.SourceFilterStrategy;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.ClaimVectorGenerationManifest;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.GenerationStatus;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorQdrantStore.ClaimVectorHit;
import com.example.requirementrag.retrieval.EmbeddingBatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaimVectorCandidateAdapterTest {

    @TempDir
    Path tempDir;

    private MultiSourceKnowledgeStore knowledgeStore;
    private SQLiteKnowledgeClaimVectorStore vectorStore;
    private KnowledgeClaimVectorQdrantStore qdrantStore;
    private EmbeddingBatcher embeddingBatcher;
    private SourceFilterStrategy sourceFilter;
    private KnowledgeClaimVectorProperties properties;
    private ClaimVectorCandidateAdapter adapter;

    @BeforeEach
    void setUp() {
        knowledgeStore = mock(MultiSourceKnowledgeStore.class);
        vectorStore = mock(SQLiteKnowledgeClaimVectorStore.class);
        qdrantStore = mock(KnowledgeClaimVectorQdrantStore.class);
        embeddingBatcher = mock(EmbeddingBatcher.class);
        sourceFilter = new SourceFilterStrategy();
        properties = new KnowledgeClaimVectorProperties(
                true, true, true, true,
                "knowledge_claims_live", "knowledge-claim-vector-v1", "knowledge-claim-text-v1",
                200, 3, 32, 3, 2, tempDir.resolve("test-adapter.db").toString());
        adapter = new ClaimVectorCandidateAdapter(
                knowledgeStore, vectorStore, qdrantStore, embeddingBatcher, sourceFilter, properties);
    }

    private KnowledgeClaimRecord claim(String id, SourceType type) {
        return new KnowledgeClaimRecord(
                id, "proj-1", "doc-ver-1", type, Authority.PRIMARY,
                "authn#login", "登录模块", "必须支持", "OAuth2", "TEXT", "",
                "ACTIVE", 0.95, null, null, "RULE", "run-1",
                "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z");
    }

    private ClaimVectorGenerationManifest activeManifest() {
        return new ClaimVectorGenerationManifest(
                "gen-active", "proj-1", "v1", "fp-1",
                "knowledge-claim-vector-v1", "knowledge-claim-text-v1",
                "test-model", 8, "knowledge_claims_live-timestamp",
                GenerationStatus.ACTIVE, 2, 2, "[]",
                "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z",
                "2025-01-01T00:00:00Z");
    }

    private ClaimVectorHit hit(String claimId, double score) {
        return new ClaimVectorHit(claimId, score,
                new KnowledgeClaimVectorModels.KnowledgeClaimVectorPoint(
                        "proj-1", "v1", claimId, "doc-ver-1",
                        SourceType.REQUIREMENT, Authority.PRIMARY, "ACTIVE",
                        "authn#login", "登录模块", "必须支持", "TEXT", "",
                        List.of(), "gen-active", "knowledge-claim-vector-v1",
                        "test-model", "text-hash"));
    }

    // ── sourceType ─────────────────────────────────────────────────────

    @Test
    void sourceTypeIsClaimVector() {
        assertThat(adapter.sourceType()).isEqualTo(SourceType.CLAIM_VECTOR);
    }

    // ── happy path ─────────────────────────────────────────────────────

    @Test
    void loadReturnsHydratedCandidatesWithBuildIds() {
        when(vectorStore.findActiveGeneration("proj-1", "v1"))
                .thenReturn(Optional.of(activeManifest()));
        when(embeddingBatcher.embedAll(List.of("登录")))
                .thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(qdrantStore.search(eq(properties.liveAlias("proj-1", "v1")), any(), anyInt()))
                .thenReturn(List.of(
                        hit("c-1", 0.95),
                        hit("c-2", 0.80)));
        when(knowledgeStore.findPublishedClaimsByIds(eq("proj-1"), eq("v1"), any()))
                .thenReturn(List.of(
                        claim("c-1", SourceType.REQUIREMENT),
                        claim("c-2", SourceType.PARAMETER_TABLE)));

        CandidateLoad loaded = adapter.loadDetailed("proj-1", "v1", "登录", null);

        assertThat(loaded.claims()).hasSize(2);
        assertThat(loaded.claims().get(0).claimId()).isEqualTo("c-1");
        assertThat(loaded.claims().get(0).sourceType()).isEqualTo(SourceType.REQUIREMENT);
        assertThat(loaded.claims().get(1).claimId()).isEqualTo("c-2");
        assertThat(loaded.claims().get(1).sourceType()).isEqualTo(SourceType.PARAMETER_TABLE);
        assertThat(loaded.warnings()).isEmpty();
        assertThat(loaded.buildIds()).containsExactly("gen-active");
    }

    // ── stale hit filtering ────────────────────────────────────────────

    @Test
    void staleHitsNotInSQLiteAreFiltered() {
        when(vectorStore.findActiveGeneration("proj-1", "v1"))
                .thenReturn(Optional.of(activeManifest()));
        when(embeddingBatcher.embedAll(any()))
                .thenReturn(List.of(new float[]{0.1f}));
        when(qdrantStore.search(anyString(), any(), anyInt()))
                .thenReturn(List.of(
                        hit("c-1", 0.95),
                        hit("c-deleted", 0.80)));
        when(knowledgeStore.findPublishedClaimsByIds(eq("proj-1"), eq("v1"), any()))
                .thenReturn(List.of(claim("c-1", SourceType.REQUIREMENT)));

        CandidateLoad loaded = adapter.loadDetailed("proj-1", "v1", "登录", null);

        assertThat(loaded.claims()).hasSize(1);
        assertThat(loaded.claims().get(0).claimId()).isEqualTo("c-1");
    }

    // ── disabled ───────────────────────────────────────────────────────

    @Test
    void disabledReturnsWarningAndNoCandidates() {
        properties = new KnowledgeClaimVectorProperties(
                true, true, false, true,
                "knowledge_claims_live", "knowledge-claim-vector-v1", "knowledge-claim-text-v1",
                200, 3, 32, 3, 2, tempDir.resolve("test-disabled.db").toString());
        adapter = new ClaimVectorCandidateAdapter(
                knowledgeStore, vectorStore, qdrantStore, embeddingBatcher, sourceFilter, properties);

        CandidateLoad loaded = adapter.loadDetailed("proj-1", "v1", "登录", null);

        assertThat(loaded.claims()).isEmpty();
        assertThat(loaded.warnings()).hasSize(1);
        assertThat(loaded.warnings().get(0)).startsWith("CLAIM_VECTOR_CANDIDATE_RETRIEVAL_DISABLED");
        verify(qdrantStore, never()).search(anyString(), any(), anyInt());
    }

    // ── no active generation ───────────────────────────────────────────

    @Test
    void noActiveGenerationReturnsWarning() {
        when(vectorStore.findActiveGeneration("proj-1", "v1"))
                .thenReturn(Optional.empty());

        CandidateLoad loaded = adapter.loadDetailed("proj-1", "v1", "登录", null);

        assertThat(loaded.claims()).isEmpty();
        assertThat(loaded.warnings()).hasSize(1);
        assertThat(loaded.warnings().get(0)).startsWith("CLAIM_VECTOR_NO_ACTIVE_GENERATION");
        verify(embeddingBatcher, never()).embedAll(any());
    }

    // ── empty query ────────────────────────────────────────────────────

    @Test
    void emptyQueryReturnsEmptyCandidatesWithBuildIds() {
        when(vectorStore.findActiveGeneration("proj-1", "v1"))
                .thenReturn(Optional.of(activeManifest()));

        CandidateLoad loaded = adapter.loadDetailed("proj-1", "v1", "", null);

        assertThat(loaded.claims()).isEmpty();
        assertThat(loaded.warnings()).isEmpty();
        assertThat(loaded.buildIds()).containsExactly("gen-active");
        verify(embeddingBatcher, never()).embedAll(any());
    }

    // ── Qdrant failure ─────────────────────────────────────────────────

    @Test
    void qdrantSearchFailureReturnsWarningNotException() {
        when(vectorStore.findActiveGeneration("proj-1", "v1"))
                .thenReturn(Optional.of(activeManifest()));
        when(embeddingBatcher.embedAll(any()))
                .thenReturn(List.of(new float[]{0.1f}));
        when(qdrantStore.search(anyString(), any(), anyInt()))
                .thenThrow(new RuntimeException("Qdrant timeout"));

        CandidateLoad loaded = adapter.loadDetailed("proj-1", "v1", "登录", null);

        assertThat(loaded.claims()).isEmpty();
        assertThat(loaded.warnings()).hasSize(1);
        assertThat(loaded.warnings().get(0)).startsWith("CLAIM_VECTOR_SEARCH_FAILED");
        assertThat(loaded.buildIds()).containsExactly("gen-active");
    }

    // ── zero hits ──────────────────────────────────────────────────────

    @Test
    void zeroHitsReturnsEmptyCandidatesWithBuildIds() {
        when(vectorStore.findActiveGeneration("proj-1", "v1"))
                .thenReturn(Optional.of(activeManifest()));
        when(embeddingBatcher.embedAll(any()))
                .thenReturn(List.of(new float[]{0.1f}));
        when(qdrantStore.search(anyString(), any(), anyInt()))
                .thenReturn(List.of());

        CandidateLoad loaded = adapter.loadDetailed("proj-1", "v1", "登录", null);

        assertThat(loaded.claims()).isEmpty();
        assertThat(loaded.warnings()).isEmpty();
        assertThat(loaded.buildIds()).containsExactly("gen-active");
    }

    // ── preserves original sourceType ──────────────────────────────────

    @Test
    void candidatesPreserveOriginalSourceTypeNotClaimVector() {
        when(vectorStore.findActiveGeneration("proj-1", "v1"))
                .thenReturn(Optional.of(activeManifest()));
        when(embeddingBatcher.embedAll(any()))
                .thenReturn(List.of(new float[]{0.1f}));
        when(qdrantStore.search(anyString(), any(), anyInt()))
                .thenReturn(List.of(
                        hit("c-1", 0.95),
                        hit("c-2", 0.80),
                        hit("c-3", 0.70)));
        when(knowledgeStore.findPublishedClaimsByIds(eq("proj-1"), eq("v1"), any()))
                .thenReturn(List.of(
                        claim("c-1", SourceType.REQUIREMENT),
                        claim("c-2", SourceType.PARAMETER_TABLE),
                        claim("c-3", SourceType.DOUBT)));

        CandidateLoad loaded = adapter.loadDetailed("proj-1", "v1", "查询", null);

        assertThat(loaded.claims()).hasSize(3);
        assertThat(loaded.claims().get(0).sourceType()).isEqualTo(SourceType.REQUIREMENT);
        assertThat(loaded.claims().get(1).sourceType()).isEqualTo(SourceType.PARAMETER_TABLE);
        assertThat(loaded.claims().get(2).sourceType()).isEqualTo(SourceType.DOUBT);
        // CLAIM_VECTOR 不出现在任何候选的 sourceType 中
        assertThat(loaded.claims()).noneMatch(c -> c.sourceType() == SourceType.CLAIM_VECTOR);
    }
}
