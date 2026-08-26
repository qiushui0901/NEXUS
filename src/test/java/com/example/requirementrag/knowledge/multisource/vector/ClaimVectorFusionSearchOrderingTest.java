package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.CrossSourceRelationConfirmer;
import com.example.requirementrag.knowledge.multisource.CrossSourceRelationExtractor;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.KnowledgeQueryIntentClassifier;
import com.example.requirementrag.knowledge.multisource.MultiSourceConflictAnalyzer;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeGate;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.MultiSourceSearchResponse;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeProperties;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.knowledge.multisource.MultiSourceSearchService;
import com.example.requirementrag.knowledge.multisource.SourceFilterStrategy;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.ClaimVectorGenerationManifest;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.GenerationStatus;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorQdrantStore.ClaimVectorHit;
import com.example.requirementrag.retrieval.EmbeddingBatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.when;

/**
 * 主搜索端到端融合排序与治理测试（高：Review 4/5——旧实现向量候选绕过 gate 状态门禁，
 * 且融合分数被丢弃导致向量相似度不参与最终排序）。
 * <p>通过真实 {@link MultiSourceSearchService} 验证：</p>
 * <ol>
 *   <li>REJECTED 状态的向量候选在融合后仍被 gate 过滤，不会进入结果（Review 4）。</li>
 *   <li>最终排序使用融合分数——高向量相似度的候选排在低相似度之前，
 *       即使两者都没有词面命中（Review 5）。</li>
 *   <li>向量适配器只被调用一次（每次请求不重复 embedding/Qdrant/水化）。</li>
 * </ol>
 */
class ClaimVectorFusionSearchOrderingTest {

    @TempDir
    Path tempDir;

    private static final String LIVE_ALIAS = "knowledge_claims_live-proj-1-v1";

    private SQLiteKnowledgeClaimVectorStore vectorStore;
    private KnowledgeClaimVectorQdrantStore qdrantStore;
    private EmbeddingBatcher embeddingBatcher;
    private MultiSourceKnowledgeStore hydrationStore;
    private KnowledgeClaimVectorProperties properties;
    private ClaimVectorCandidateAdapter adapter;
    private MultiSourceSearchService searchService;
    private MultiSourceKnowledgeStore realStore;

    @BeforeEach
    void setUp() {
        vectorStore = mock(SQLiteKnowledgeClaimVectorStore.class);
        qdrantStore = mock(KnowledgeClaimVectorQdrantStore.class);
        embeddingBatcher = mock(EmbeddingBatcher.class);
        hydrationStore = mock(MultiSourceKnowledgeStore.class);
        properties = new KnowledgeClaimVectorProperties(
                true, true, true, true,
                "knowledge_claims_live", "knowledge-claim-vector-v1", "knowledge-claim-text-v1",
                200, 3, 32, 3, 2, tempDir.resolve("fusion.db").toString());
        adapter = new ClaimVectorCandidateAdapter(
                hydrationStore, vectorStore, qdrantStore, embeddingBatcher,
                new SourceFilterStrategy(), properties);

        ClaimVectorGenerationManifest active = new ClaimVectorGenerationManifest(
                "gen-1", "proj-1", "v1", "fp-1",
                "knowledge-claim-vector-v1", "knowledge-claim-text-v1",
                "model:8", 8, "knowledge_claims_live-proj-1-v1-1",
                GenerationStatus.ACTIVE, 3, 3, "[]",
                "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z",
                "2025-01-01T00:00:00Z");
        when(vectorStore.findActiveGeneration("proj-1", "v1")).thenReturn(Optional.of(active));
        when(embeddingBatcher.embedAll(any())).thenReturn(List.of(new float[]{0.1f, 0.2f}));

        realStore = new MultiSourceKnowledgeStore(
                tempDir.resolve("search.db").toString(), new ObjectMapper());
        searchService = new MultiSourceSearchService(realStore,
                new KnowledgeQueryIntentClassifier(), new MultiSourceKnowledgeGate(),
                new SourceFilterStrategy(), new MultiSourceConflictAnalyzer(),
                List.of(), new CrossSourceRelationExtractor(),
                query -> Optional.empty(), MultiSourceKnowledgeProperties.enabledDefault(),
                (source, relationType, target, evidence) ->
                        new CrossSourceRelationConfirmer.Confirmation(true, "no-op"),
                new KnowledgeClaimVectorFusion(), new ClaimVectorShadowEvaluator(), adapter);
    }

    private KnowledgeClaimRecord record(String id, String status) {
        return new KnowledgeClaimRecord(
                id, "proj-1", "doc-1", SourceType.REQUIREMENT, Authority.PRIMARY,
                "authn#login", "系统需要支持登录", "必须支持", "", "", "", status,
                0.9, null, null, "RULE", "run-1",
                "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z");
    }

    private ClaimVectorHit hit(String claimId, double score) {
        return new ClaimVectorHit(claimId, score, new KnowledgeClaimVectorModels.KnowledgeClaimVectorPoint(
                "proj-1", "v1", claimId, "doc-1",
                SourceType.REQUIREMENT, Authority.PRIMARY, "ACTIVE",
                "authn#login", "系统需要支持登录", "必须支持", "", "",
                List.of(), "gen-1", "knowledge-claim-vector-v1", "model:8", "hash"));
    }

    private void stubHitsAndHydration() {
        when(qdrantStore.search(eq(LIVE_ALIAS), any(), anyInt())).thenReturn(List.of(
                hit("c-v1", 0.95),
                hit("c-rejected", 0.70),
                hit("c-v2", 0.55)));
        when(hydrationStore.findClaimsByIds(any())).thenReturn(List.of(
                record("c-v1", "ACTIVE"),
                record("c-v2", "ACTIVE"),
                record("c-rejected", "REJECTED")));
    }

    /** 高（Review 4）：REJECTED 向量候选被 gate 过滤；Review 5：按融合分数排序。 */
    @Test
    void gateFiltersRejectedVectorCandidateAndFusionScoreRanks() {
        stubHitsAndHydration();

        MultiSourceSearchResponse response = searchService.search(
                "proj-1", "v1", "登录语义相关查询", KnowledgeQueryIntent.NORMATIVE, 20, 0);

        // 结果只含可检索的向量候选（c-v1、c-v2），REJECTED 被 gate 过滤（Review 4）
        assertThat(response.claims()).hasSize(2);
        // 融合分数排序：c-v1(归一化 1.0→0.65) 高于 c-v2(归一化 0.0→0.10)——高向量相似度在前（Review 5）
        assertThat(response.claims().get(0).claimId()).isEqualTo("c-v1");
        assertThat(response.claims().get(1).claimId()).isEqualTo("c-v2");
    }

    /** 向量适配器每次请求只执行一次（避免重复 embedding/Qdrant/水化）。 */
    @Test
    void vectorAdapterLoadedExactlyOncePerSearch() {
        stubHitsAndHydration();

        searchService.search("proj-1", "v1", "登录", KnowledgeQueryIntent.NORMATIVE, 20, 0);

        // 一次 embedAll（查询向量）+ 一次 search + 一次 findClaimsByIds
        org.mockito.Mockito.verify(embeddingBatcher, org.mockito.Mockito.times(1))
                .embedAll(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(qdrantStore, org.mockito.Mockito.times(1))
                .search(anyString(), any(), anyInt());
        org.mockito.Mockito.verify(hydrationStore, org.mockito.Mockito.times(1))
                .findClaimsByIds(any());
    }

    /** 意图过滤（Review 4）：NORMATIVE 意图不允许 DOUBT 来源，DOUBT 向量候选被丢弃。 */
    @Test
    void intentFilteringDropsDoubtVectorCandidateForNormativeQuery() {
        when(qdrantStore.search(anyString(), any(), anyInt())).thenReturn(List.of(
                hit("c-doubt", 0.90),
                hit("c-req", 0.80)));
        when(hydrationStore.findClaimsByIds(any())).thenReturn(List.of(
                new KnowledgeClaimRecord("c-doubt", "proj-1", "doc-1",
                        SourceType.DOUBT, Authority.PRIMARY, "doubt#q", "该参数是否必要",
                        "", "", "", "", "OPEN", 0.5, null, null,
                        "RULE", "run-1", "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z"),
                record("c-req", "ACTIVE")));

        MultiSourceSearchResponse response = searchService.search(
                "proj-1", "v1", "参数必要性", KnowledgeQueryIntent.NORMATIVE, 20, 0);

        // DOUBT 候选被意图过滤排除，只剩 REQUIREMENT
        assertThat(response.claims()).hasSize(1);
        assertThat(response.claims().get(0).claimId()).isEqualTo("c-req");
        assertThat(response.claims().get(0).sourceType()).isEqualTo(SourceType.REQUIREMENT);
    }
}
