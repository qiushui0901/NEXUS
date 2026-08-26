package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceCandidateAdapter.CandidateLoad;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 确定性融合组件——按 claimId 去重，加权评分，稳定排序。
 *
 * <p>融合公式（方案 §7.2）：
 * <pre>
 * finalScore =
 *   0.55 * normalizedVectorScore
 * + 0.25 * lexicalFieldScore
 * + 0.10 * sourcePolicyWeight
 * + 0.10 * exactFactOrSubjectBoost
 * - existingConflictPenalty
 * </pre>
 *
 * <p>稳定排序：score desc → sourceType → factKey → claimId。
 * 分页仅发生在融合+去重+治理过滤+稳定排序之后。
 */
@Component
public class KnowledgeClaimVectorFusion {

    /** 默认权重：向量 0.55 / 词法 0.25 / 策略 0.10 / 精确 0.10。 */
    public static final double[] DEFAULT_WEIGHTS = {0.55, 0.25, 0.10, 0.10};

    /** 治理状态惩罚——冲突状态扣分。 */
    private static final double CONFLICT_PENALTY = 0.10;

    /** 精确命中加分的最高值。 */
    private static final double EXACT_BOOST = 1.0;

    /**
     * 融合向量候选与直接（结构化）候选。
     *
     * @param vectorLoad       向量适配器返回的带分候选（可为空——向量检索未启用或零命中）
     * @param directClaims     直接加载的结构化候选（可为空）
     * @param query            用户查询文本（用于精确命中加分，可为 null）
     * @return 融合结果——去重后的候选列表按融合分数稳定排序
     */
    public FusionResult fuse(ScoredCandidateLoad vectorLoad,
                             List<UnifiedKnowledgeClaim> directClaims,
                             String query) {
        return fuse(vectorLoad, directClaims, query, DEFAULT_WEIGHTS);
    }

    /**
     * 融合——可配置权重（用于调参实验，非生产路径）。
     *
     * @param weights [vectorWeight, lexicalWeight, policyWeight, exactWeight]
     */
    public FusionResult fuse(ScoredCandidateLoad vectorLoad,
                             List<UnifiedKnowledgeClaim> directClaims,
                             String query,
                             double[] weights) {
        Objects.requireNonNull(weights, "weights must not be null");
        if (weights.length != 4) {
            throw new IllegalArgumentException("weights must have exactly 4 elements [vec, lex, policy, exact]");
        }

        List<UnifiedKnowledgeClaim> vecClaims = vectorLoad != null ? vectorLoad.claims() : List.of();
        Map<String, Double> vecScores = vectorLoad != null ? vectorLoad.scores() : Map.of();
        List<UnifiedKnowledgeClaim> direct = directClaims != null ? directClaims : List.of();

        // 归一化向量分数（min-max 到 [0, 1]）
        Map<String, Double> normalizedVecScores = normalizeVectorScores(vecClaims, vecScores);

        // 合并——按 claimId 去重，保留首个出现的候选对象（分数合并）
        // 向量路径优先（分数信息更丰富），直接路径补充
        Map<String, UnifiedKnowledgeClaim> claimMap = new LinkedHashMap<>();
        boolean[] fromVector = new boolean[0]; // 不用

        for (UnifiedKnowledgeClaim c : vecClaims) {
            claimMap.putIfAbsent(c.claimId(), c);
        }
        for (UnifiedKnowledgeClaim c : direct) {
            claimMap.putIfAbsent(c.claimId(), c);
        }

        // 计算每个候选的融合分数
        List<ScoredClaim> scoredClaims = new ArrayList<>();
        int duplicateRemoved = (vecClaims.size() + direct.size()) - claimMap.size();

        for (UnifiedKnowledgeClaim claim : claimMap.values()) {
            String claimId = claim.claimId();

            double vecScore = normalizedVecScores.getOrDefault(claimId, 0.0);
            // 直接候选的词法分数=1.0（查询已匹配），向量候选且不在直接列表中的词法分数=0.0
            double lexScore = isDirectClaim(claim.claimId(), direct) ? 1.0 : 0.0;
            double policyWeight = sourcePolicyWeight(claim.authority());
            double exactBoost = exactMatchBoost(query, claim);
            double conflictPenalty = conflictPenalty(claim.status());

            double finalScore =
                    weights[0] * vecScore
                    + weights[1] * lexScore
                    + weights[2] * policyWeight
                    + weights[3] * exactBoost
                    - conflictPenalty;

            scoredClaims.add(new ScoredClaim(claim, finalScore));
        }

        // 稳定排序：score desc → sourceType → factKey → claimId
        scoredClaims.sort(FusionComparator.INSTANCE);

        List<UnifiedKnowledgeClaim> fused = scoredClaims.stream()
                .map(ScoredClaim::claim)
                .toList();

        String weightsSnapshot = String.format(
                "vec=%.2f,lex=%.2f,policy=%.2f,exact=%.2f",
                weights[0], weights[1], weights[2], weights[3]);

        return new FusionResult(
                fused,
                vecClaims.size(),
                direct.size(),
                duplicateRemoved,
                weightsSnapshot);
    }

    // ── 内部计算 ──────────────────────────────────────────────────────

    private Map<String, Double> normalizeVectorScores(List<UnifiedKnowledgeClaim> vecClaims,
                                                       Map<String, Double> rawScores) {
        if (rawScores.isEmpty() || vecClaims.isEmpty()) {
            return Map.of();
        }
        double min = rawScores.values().stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double max = rawScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        double range = max - min;
        Map<String, Double> normalized = new HashMap<>();
        for (UnifiedKnowledgeClaim c : vecClaims) {
            double raw = rawScores.getOrDefault(c.claimId(), 0.0);
            // 归一化到 [0, 1]；范围为 0 时全部映射为 1.0（全部等分）
            normalized.put(c.claimId(), range > 0 ? (raw - min) / range : 1.0);
        }
        return normalized;
    }

    private boolean isDirectClaim(String claimId, List<UnifiedKnowledgeClaim> direct) {
        for (UnifiedKnowledgeClaim c : direct) {
            if (c.claimId().equals(claimId)) {
                return true;
            }
        }
        return false;
    }

    private double sourcePolicyWeight(Authority authority) {
        if (authority == null) {
            return 0.0;
        }
        return switch (authority) {
            case PRIMARY -> 1.0;
            case SECONDARY -> 0.8;
            case DERIVED -> 0.6;
        };
    }

    private double exactMatchBoost(String query, UnifiedKnowledgeClaim claim) {
        if (query == null || query.isBlank()) {
            return 0.0;
        }
        String q = query.trim().toLowerCase();
        if (q.isEmpty()) {
            return 0.0;
        }
        // 精确命中 factKey 或 subject → 加分
        if (claim.factKey() != null && claim.factKey().toLowerCase().contains(q)) {
            return EXACT_BOOST;
        }
        if (claim.subject() != null && claim.subject().toLowerCase().contains(q)) {
            return EXACT_BOOST;
        }
        return 0.0;
    }

    private double conflictPenalty(KnowledgeStatus status) {
        if (status == KnowledgeStatus.CONFLICTED) {
            return CONFLICT_PENALTY;
        }
        return 0.0;
    }

    // ── 内部类型 ──────────────────────────────────────────────────────

    private record ScoredClaim(UnifiedKnowledgeClaim claim, double score) {}

    private static final class FusionComparator implements Comparator<ScoredClaim> {
        static final FusionComparator INSTANCE = new FusionComparator();

        @Override
        public int compare(ScoredClaim a, ScoredClaim b) {
            // score desc
            int byScore = Double.compare(b.score, a.score);
            if (byScore != 0) return byScore;
            // sourceType asc (alphabetical)
            String stA = a.claim().sourceType() != null ? a.claim().sourceType().name() : "";
            String stB = b.claim().sourceType() != null ? b.claim().sourceType().name() : "";
            int bySource = stA.compareTo(stB);
            if (bySource != 0) return bySource;
            // factKey asc
            String fkA = a.claim().factKey() != null ? a.claim().factKey() : "";
            String fkB = b.claim().factKey() != null ? b.claim().factKey() : "";
            int byFact = fkA.compareTo(fkB);
            if (byFact != 0) return byFact;
            // claimId asc (final tie-break)
            return a.claim().claimId().compareTo(b.claim().claimId());
        }
    }

    // ── 公共类型 ──────────────────────────────────────────────────────

    /**
     * 带分候选载荷——向量适配器返回的候选 + Qdrant 原始分数。
     */
    public record ScoredCandidateLoad(
            CandidateLoad load,
            Map<String, Double> scores  // claimId → Qdrant 原始 cosine 分数
    ) {
        public ScoredCandidateLoad {
            if (load == null) {
                throw new IllegalArgumentException("load must not be null");
            }
            scores = scores != null ? scores : Map.of();
        }

        public List<UnifiedKnowledgeClaim> claims() {
            return load.claims();
        }

        public List<String> warnings() {
            return load.warnings();
        }

        public List<String> buildIds() {
            return load.buildIds();
        }
    }

    /**
     * 融合结果——去重+加权+稳定排序后的候选列表 + 来源统计。
     */
    public record FusionResult(
            List<UnifiedKnowledgeClaim> candidates,
            int vectorHitCount,
            int directHitCount,
            int duplicateRemovedCount,
            String fusionWeightsSnapshot
    ) {
        public FusionResult {
            candidates = candidates != null ? candidates : List.of();
        }
    }
}
