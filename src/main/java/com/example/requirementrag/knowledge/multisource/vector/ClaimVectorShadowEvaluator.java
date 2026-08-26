package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 影子模式评估器——在不影响主检索路径的前提下，记录向量候选与结构化候选的对比指标。
 *
 * <p>仅在 {@code app.rag.multi-source.claim-vector.shadow-query-enabled=true} 时激活。
 * 记录每次查询的向量命中数、结构化命中数、重合数、响应时间，
 * 并按 projectId+version 聚合统计供 operator 审阅。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.rag.multi-source.claim-vector",
        name = "shadow-query-enabled",
        matchIfMissing = false)
public class ClaimVectorShadowEvaluator {

    private final Map<String, ScopeStats> scopeStats = new ConcurrentHashMap<>();
    private final Map<String, List<QueryRecord>> recentQueries = new ConcurrentHashMap<>();

    /**
     * 记录一次影子查询的对比结果。
     *
     * @param projectId          项目 ID
     * @param version           业务版本
     * @param query             查询文本
     * @param vectorCandidates  向量候选列表（可为空）
     * @param directCandidates  结构化候选列表（可为空）
     * @param elapsedMs         查询耗时（毫秒）
     */
    public void recordQuery(String projectId, String version, String query,
                            List<UnifiedKnowledgeClaim> vectorCandidates,
                            List<UnifiedKnowledgeClaim> directCandidates,
                            long elapsedMs) {
        String scopeKey = scopeKey(projectId, version);
        Set<String> vectorIds = vectorCandidates != null
                ? vectorCandidates.stream().map(UnifiedKnowledgeClaim::claimId).collect(Collectors.toSet())
                : Set.of();
        Set<String> directIds = directCandidates != null
                ? directCandidates.stream().map(UnifiedKnowledgeClaim::claimId).collect(Collectors.toSet())
                : Set.of();

        int vectorCount = vectorIds.size();
        int directCount = directIds.size();
        int overlap = (int) vectorIds.stream().filter(directIds::contains).count();
        int vectorOnly = vectorCount - overlap;
        int directOnly = directCount - overlap;
        boolean vectorAddsRecall = vectorOnly > 0;

        QueryRecord record = new QueryRecord(
                query, vectorCount, directCount, overlap,
                vectorOnly, directOnly, vectorAddsRecall, elapsedMs);

        scopeStats.compute(scopeKey, (k, existing) -> {
            if (existing == null) {
                return new ScopeStats(
                        projectId, version,
                        1, vectorCount, directCount, overlap,
                        vectorAddsRecall ? 1 : 0, elapsedMs);
            }
            return new ScopeStats(
                    projectId, version,
                    existing.queryCount() + 1,
                    existing.totalVectorHits() + vectorCount,
                    existing.totalDirectHits() + directCount,
                    existing.totalOverlap() + overlap,
                    existing.queriesWithVectorOnlyHits() + (vectorAddsRecall ? 1 : 0),
                    existing.totalElapsedMs() + elapsedMs);
        });

        recentQueries.computeIfAbsent(scopeKey, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(record);
        if (recentQueries.get(scopeKey).size() > 100) {
            // 保留最近 100 条查询记录
            List<QueryRecord> list = recentQueries.get(scopeKey);
            while (list.size() > 100) {
                list.remove(0);
            }
        }
    }

    /**
     * 获取某 scope 的聚合指标。
     */
    public ScopeStats scopeMetric(String projectId, String version) {
        return scopeStats.get(scopeKey(projectId, version));
    }

    /**
     * 获取某 scope 的最近查询记录（只读）。
     */
    public List<QueryRecord> recentQueries(String projectId, String version) {
        List<QueryRecord> list = recentQueries.get(scopeKey(projectId, version));
        return list != null ? List.copyOf(list) : List.of();
    }

    /**
     * 发布就绪判断——需至少 20 条影子查询，且向量新增召回的查询比例 ≥ 30%。
     */
    public boolean publishIfReady(String projectId, String version) {
        ScopeStats stats = scopeStats.get(scopeKey(projectId, version));
        if (stats == null || stats.queryCount() < 20) {
            return false;
        }
        double vectorRecallRate = (double) stats.queriesWithVectorOnlyHits() / stats.queryCount();
        return vectorRecallRate >= 0.30;
    }

    private String scopeKey(String projectId, String version) {
        return projectId + "|" + version;
    }

    // ── 公共类型 ──────────────────────────────────────────────────────

    /** 单次影子查询记录。 */
    public record QueryRecord(
            String query,
            int vectorHitCount,
            int directHitCount,
            int overlapCount,
            int vectorOnlyCount,
            int directOnlyCount,
            boolean vectorAddsRecall,
            long elapsedMs
    ) {}

    /** scope 级别聚合统计。 */
    public record ScopeStats(
            String projectId,
            String version,
            int queryCount,
            int totalVectorHits,
            int totalDirectHits,
            int totalOverlap,
            int queriesWithVectorOnlyHits,
            long totalElapsedMs
    ) {
        public double avgVectorHitsPerQuery() {
            return queryCount > 0 ? (double) totalVectorHits / queryCount : 0.0;
        }

        public double avgDirectHitsPerQuery() {
            return queryCount > 0 ? (double) totalDirectHits / queryCount : 0.0;
        }

        public double vectorRecallContributionRate() {
            return queryCount > 0 ? (double) queriesWithVectorOnlyHits / queryCount : 0.0;
        }

        public long avgElapsedMs() {
            return queryCount > 0 ? totalElapsedMs / queryCount : 0;
        }
    }
}
