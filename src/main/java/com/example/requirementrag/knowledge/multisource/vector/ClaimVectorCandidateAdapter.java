package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceCandidateAdapter;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.knowledge.multisource.SourceFilterStrategy;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.ClaimVectorGenerationManifest;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorQdrantStore.ClaimVectorHit;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.retrieval.EmbeddingBatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Claim 向量候选适配器（§10 Phase C）：通过 Qdrant 向量检索召回 Claim，
 * 再从 SQLite 权威存储重新读取治理字段（status/authority 等不依赖 Qdrant payload）。
 * <p>
 * 一个点代表一个 Claim；命中后用 claimId 回查 SQLite，过滤已删除窗口的 stale 记录。
 * 返回的 {@link UnifiedKnowledgeClaim} 保留原始 sourceType（REQUIREMENT/PARAMETER_TABLE/TEST_CASE/DOUBT），
 * 与直接加载的候选按 equals 自然去重——Claim 向量只补充直接加载未命中的 Claim。
 * <p>
 * 安全保证：
 * <ul>
 *   <li>高（Review 2）：检索使用按 project+version 隔离的 live alias；水化后校验 claim.projectId 与
 *       请求一致、代际 payload 版本与请求一致，杜绝跨 scope 泄漏。</li>
 *   <li>高（Review 4）：接收 intent，按 SourceFilterStrategy 过滤原始来源类型——NORMATIVE 查询不会
 *       混入 DOUBT Claim；被 gate 排除的状态（REJECTED/STALE/OBSOLETE）在 SearchService 融合后统一过滤。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "app.rag.multi-source.claim-vector",
        name = {"enabled", "candidate-retrieval-enabled"}, havingValue = "true", matchIfMissing = false)
public class ClaimVectorCandidateAdapter implements MultiSourceCandidateAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClaimVectorCandidateAdapter.class);

    private final MultiSourceKnowledgeStore knowledgeStore;
    private final SQLiteKnowledgeClaimVectorStore vectorStore;
    private final KnowledgeClaimVectorQdrantStore qdrantStore;
    private final EmbeddingBatcher embeddingBatcher;
    private final SourceFilterStrategy sourceFilter;
    private final KnowledgeClaimVectorProperties properties;

    public ClaimVectorCandidateAdapter(MultiSourceKnowledgeStore knowledgeStore,
                                       SQLiteKnowledgeClaimVectorStore vectorStore,
                                       KnowledgeClaimVectorQdrantStore qdrantStore,
                                       EmbeddingBatcher embeddingBatcher,
                                       SourceFilterStrategy sourceFilter,
                                       KnowledgeClaimVectorProperties properties) {
        this.knowledgeStore = knowledgeStore;
        this.vectorStore = vectorStore;
        this.qdrantStore = qdrantStore;
        this.embeddingBatcher = embeddingBatcher;
        this.sourceFilter = sourceFilter;
        this.properties = properties;
    }

    @Override
    public SourceType sourceType() {
        return SourceType.CLAIM_VECTOR;
    }

    @Override
    public List<UnifiedKnowledgeClaim> load(String projectId, String version, String query) {
        return loadDetailed(projectId, version, query, null).claims();
    }

    @Override
    public CandidateLoad loadDetailed(String projectId, String version, String query,
                                      KnowledgeQueryIntent intent) {
        return doSearch(projectId, version, query, intent).load();
    }

    /**
     * 带分加载——返回 Qdrant 原始 cosine 分数供 {@link KnowledgeClaimVectorFusion} 融合使用。
     *
     * <p>仅在融合路径调用；普通 {@link #loadDetailed} 不携带分数。
     *
     * @param intent 查询意图（高：Review 4——用于过滤该意图不允许的原始来源类型）
     * @return 带分候选载荷（scores: claimId → Qdrant 原始分数）
     */
    public KnowledgeClaimVectorFusion.ScoredCandidateLoad loadScored(
            String projectId, String version, String query, KnowledgeQueryIntent intent) {
        SearchResult result = doSearch(projectId, version, query, intent);
        return new KnowledgeClaimVectorFusion.ScoredCandidateLoad(result.load(), result.scores());
    }

    /** 后向兼容：无意图的带分加载（不按意图过滤来源类型）。 */
    public KnowledgeClaimVectorFusion.ScoredCandidateLoad loadScored(
            String projectId, String version, String query) {
        return loadScored(projectId, version, query, null);
    }

    /**
     * 核心检索逻辑——返回候选载荷 + Qdrant 原始分数映射。
     */
    private SearchResult doSearch(String projectId, String version, String query,
                                  KnowledgeQueryIntent intent) {
        if (!properties.candidateRetrievalEnabled()) {
            return new SearchResult(new CandidateLoad(List.of(),
                    List.of("CLAIM_VECTOR_CANDIDATE_RETRIEVAL_DISABLED:Claim 向量候选检索已关闭（candidate-retrieval-enabled=false），本次结果不含向量召回候选"),
                    List.of()), Map.of());
        }

        Optional<ClaimVectorGenerationManifest> active =
                vectorStore.findActiveGeneration(projectId, version);
        if (active.isEmpty()) {
            return new SearchResult(new CandidateLoad(List.of(),
                    List.of("CLAIM_VECTOR_NO_ACTIVE_GENERATION:项目 " + safe(projectId)
                            + " 版本 " + safe(version) + " 无活跃 Claim 向量代际，跳过向量召回"),
                    List.of()), Map.of());
        }

        if (query == null || query.isBlank()) {
            return new SearchResult(new CandidateLoad(List.of(), List.of(),
                    List.of(active.get().generationId())), Map.of());
        }

        try {
            List<float[]> embeddings = embeddingBatcher.embedAll(List.of(query));
            if (embeddings.isEmpty() || embeddings.get(0).length == 0) {
                return new SearchResult(new CandidateLoad(List.of(),
                        List.of("CLAIM_VECTOR_EMBEDDING_EMPTY:查询嵌入为空，跳过向量召回"),
                        List.of(active.get().generationId())), Map.of());
            }
            float[] queryVector = embeddings.get(0);

            int limit = properties.candidateLimit() * properties.overFetchFactor();
            // 高（Review 2）：按 project+version 隔离的 live alias，避免跨 scope 检索
            String liveAlias = properties.liveAlias(projectId, version);
            List<ClaimVectorHit> hits = qdrantStore.search(liveAlias, queryVector, limit);
            if (hits.isEmpty()) {
                return new SearchResult(new CandidateLoad(List.of(), List.of(),
                        List.of(active.get().generationId())), Map.of());
            }

            List<String> claimIds = hits.stream().map(ClaimVectorHit::claimId).toList();
            Map<String, KnowledgeClaimRecord> records = knowledgeStore.findClaimsByIds(claimIds).stream()
                    .collect(Collectors.toMap(KnowledgeClaimRecord::claimId, Function.identity(), (a, b) -> a));

            List<UnifiedKnowledgeClaim> candidates = new ArrayList<>();
            Map<String, Double> scores = new LinkedHashMap<>();
            int staleCount = 0;
            int scopeMismatchCount = 0;
            int intentFilteredCount = 0;
            for (ClaimVectorHit hit : hits) {
                // payload 级版本校验（第一道防线，跨 scope 旧点防御）
                if (hit.point() != null && hit.point().businessVersion() != null
                        && !hit.point().businessVersion().equals(version)) {
                    scopeMismatchCount++;
                    continue;
                }
                KnowledgeClaimRecord record = records.get(hit.claimId());
                if (record == null) {
                    staleCount++;
                    continue;
                }
                // SQLite 水化后权威校验：project 归属（第二道防线）
                if (!projectId.equals(record.projectId())) {
                    scopeMismatchCount++;
                    continue;
                }
                if (record.documentVersionId() == null || record.documentVersionId().isBlank()) {
                    staleCount++;
                    continue;
                }
                // 意图过滤（高：Review 4——NORMATIVE 等意图不得混入 DOUBT 等不允许的来源）
                if (intent != null && !sourceFilter.allowedSources(intent).contains(record.sourceType())) {
                    intentFilteredCount++;
                    continue;
                }
                candidates.add(toUnified(record, version));
                scores.put(hit.claimId(), hit.score());
            }
            if (staleCount > 0) {
                LOGGER.warn("CLAIM_VECTOR_STALE_HITS project={} version={} stale={}/{}",
                        safe(projectId), safe(version), staleCount, hits.size());
            }
            if (scopeMismatchCount > 0) {
                LOGGER.warn("CLAIM_VECTOR_SCOPE_MISMATCH project={} version={} mismatched={}/{}",
                        safe(projectId), safe(version), scopeMismatchCount, hits.size());
            }
            if (intentFilteredCount > 0) {
                LOGGER.debug("CLAIM_VECTOR_INTENT_FILTERED project={} version={} filtered={}/{} intent={}",
                        safe(projectId), safe(version), intentFilteredCount, hits.size(), intent);
            }

            return new SearchResult(new CandidateLoad(List.copyOf(candidates), List.of(),
                    List.of(active.get().generationId())), scores);
        } catch (RuntimeException exception) {
            LOGGER.warn("CLAIM_VECTOR_SEARCH_FAILED project={} version={} error={}",
                    safe(projectId), safe(version), exception.getClass().getSimpleName());
            return new SearchResult(new CandidateLoad(List.of(),
                    List.of("CLAIM_VECTOR_SEARCH_FAILED:向量检索失败，跳过向量召回（"
                            + exception.getClass().getSimpleName() + ")"),
                    List.of(active.get().generationId())), Map.of());
        }
    }

    /** 检索结果——候选载荷 + Qdrant 分数。 */
    private record SearchResult(CandidateLoad load, Map<String, Double> scores) {}

    private UnifiedKnowledgeClaim toUnified(KnowledgeClaimRecord record, String version) {
        return new UnifiedKnowledgeClaim(
                record.claimId(),
                record.projectId(),
                version,
                record.factKey(),
                record.subject(),
                record.predicate(),
                record.objectValue(),
                record.valueType(),
                record.unit(),
                record.sourceType(),
                record.authority(),
                parseStatus(record.status()),
                record.effectiveFrom(),
                record.effectiveTo(),
                record.documentVersionId(),
                extractModule(record.factKey()),
                null);
    }

    private KnowledgeStatus parseStatus(String status) {
        if (status == null || status.isBlank()) return KnowledgeStatus.SUPPORTED;
        try {
            return KnowledgeStatus.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return KnowledgeStatus.SUPPORTED;
        }
    }

    private String extractModule(String factKey) {
        if (factKey == null || factKey.isBlank()) return "";
        int hash = factKey.indexOf('#');
        int pipe = factKey.indexOf('|');
        int end = hash < 0 ? pipe : (pipe < 0 ? hash : Math.min(hash, pipe));
        return end > 0 ? factKey.substring(0, end) : factKey;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}