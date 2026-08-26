package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceCandidateAdapter.CandidateLoad;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorFusion.FusionResult;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorFusion.ScoredCandidateLoad;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeClaimVectorFusionTest {

    private final KnowledgeClaimVectorFusion fusion = new KnowledgeClaimVectorFusion();

    private UnifiedKnowledgeClaim claim(String id, SourceType type, String factKey,
                                        String subject, Authority authority, KnowledgeStatus status) {
        return new UnifiedKnowledgeClaim(
                id, "proj-1", "v1", factKey, subject, "predicate", "value",
                "TEXT", "", type, authority, status,
                null, null, "doc-ver-1", "module", null);
    }

    private ScoredCandidateLoad scoredLoad(List<UnifiedKnowledgeClaim> claims, Map<String, Double> scores) {
        return new ScoredCandidateLoad(
                new CandidateLoad(claims, List.of(), List.of("gen-1")),
                scores);
    }

    // ── 空输入 ────────────────────────────────────────────────────────

    @Test
    void emptyVectorAndEmptyDirectReturnsEmptyResult() {
        FusionResult result = fusion.fuse(null, List.of(), "查询");
        assertThat(result.candidates()).isEmpty();
        assertThat(result.vectorHitCount()).isZero();
        assertThat(result.directHitCount()).isZero();
        assertThat(result.duplicateRemovedCount()).isZero();
    }

    @Test
    void emptyVectorWithDirectReturnsDirectCandidatesUnchanged() {
        UnifiedKnowledgeClaim c1 = claim("c-1", SourceType.REQUIREMENT, "authn#login", "登录", Authority.PRIMARY, KnowledgeStatus.SUPPORTED);
        UnifiedKnowledgeClaim c2 = claim("c-2", SourceType.PARAMETER_TABLE, "config#timeout", "超时", Authority.PRIMARY, KnowledgeStatus.SUPPORTED);

        FusionResult result = fusion.fuse(null, List.of(c1, c2), "登录");

        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates()).containsExactlyInAnyOrder(c1, c2);
        assertThat(result.vectorHitCount()).isZero();
        assertThat(result.directHitCount()).isEqualTo(2);
    }

    // ── 向量候选 ──────────────────────────────────────────────────────

    @Test
    void vectorOnlyCandidatesGetNormalizedScores() {
        UnifiedKnowledgeClaim c1 = claim("c-1", SourceType.REQUIREMENT, "authn#login", "登录", Authority.PRIMARY, KnowledgeStatus.SUPPORTED);
        UnifiedKnowledgeClaim c2 = claim("c-2", SourceType.REQUIREMENT, "authn#sso", "单点登录", Authority.PRIMARY, KnowledgeStatus.SUPPORTED);

        // c-1 has higher Qdrant score
        Map<String, Double> scores = Map.of("c-1", 0.95, "c-2", 0.80);
        ScoredCandidateLoad vectorLoad = scoredLoad(List.of(c1, c2), scores);

        FusionResult result = fusion.fuse(vectorLoad, List.of(), "登录");

        assertThat(result.candidates()).hasSize(2);
        // c-1 should rank higher (higher normalized score + exact match on subject "登录")
        assertThat(result.candidates().get(0).claimId()).isEqualTo("c-1");
        assertThat(result.candidates().get(1).claimId()).isEqualTo("c-2");
        assertThat(result.vectorHitCount()).isEqualTo(2);
        assertThat(result.directHitCount()).isZero();
    }

    // ── 去重 ──────────────────────────────────────────────────────────

    @Test
    void overlappingClaimsDeduplicatedByClaimId() {
        // Same claim appears in both vector and direct
        UnifiedKnowledgeClaim vecClaim = claim("c-1", SourceType.REQUIREMENT, "authn#login", "登录", Authority.PRIMARY, KnowledgeStatus.SUPPORTED);
        UnifiedKnowledgeClaim directClaim = claim("c-1", SourceType.REQUIREMENT, "authn#login", "登录", Authority.PRIMARY, KnowledgeStatus.SUPPORTED);

        Map<String, Double> scores = Map.of("c-1", 0.90);
        ScoredCandidateLoad vectorLoad = scoredLoad(List.of(vecClaim), scores);

        FusionResult result = fusion.fuse(vectorLoad, List.of(directClaim), "登录");

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).claimId()).isEqualTo("c-1");
        assertThat(result.duplicateRemovedCount()).isEqualTo(1);
    }

    @Test
    void nonOverlappingClaimsMergedWithoutDedup() {
        UnifiedKnowledgeClaim vecClaim = claim("c-1", SourceType.REQUIREMENT, "authn#login", "登录", Authority.PRIMARY, KnowledgeStatus.SUPPORTED);
        UnifiedKnowledgeClaim directClaim = claim("c-2", SourceType.PARAMETER_TABLE, "config#timeout", "超时", Authority.PRIMARY, KnowledgeStatus.SUPPORTED);

        Map<String, Double> scores = Map.of("c-1", 0.90);
        ScoredCandidateLoad vectorLoad = scoredLoad(List.of(vecClaim), scores);

        FusionResult result = fusion.fuse(vectorLoad, List.of(directClaim), "超时");

        assertThat(result.candidates()).hasSize(2);
        assertThat(result.duplicateRemovedCount()).isZero();
    }

    // ── 稳定排序 ──────────────────────────────────────────────────────

    @Test
    void stableTieBreakingBySourceTypeThenFactKeyThenClaimId() {
        // Same score (no vector scores, same authority, no exact match)
        UnifiedKnowledgeClaim c1 = claim("c-3", SourceType.REQUIREMENT, "zzz#key", "subj", Authority.PRIMARY, KnowledgeStatus.SUPPORTED);
        UnifiedKnowledgeClaim c2 = claim("c-1", SourceType.REQUIREMENT, "aaa#key", "subj", Authority.PRIMARY, KnowledgeStatus.SUPPORTED);
        UnifiedKnowledgeClaim c3 = claim("c-2", SourceType.PARAMETER_TABLE, "bbb#key", "subj", Authority.PRIMARY, KnowledgeStatus.SUPPORTED);

        // No query → no exact match → all same score
        // Tie-break: PARAMETER_TABLE < REQUIREMENT (alphabetical), then aaa < bbb < zzz
        FusionResult result = fusion.fuse(null, List.of(c1, c2, c3), null);

        assertThat(result.candidates()).hasSize(3);
        // sourceType asc: PARAMETER_TABLE before REQUIREMENT
        assertThat(result.candidates().get(0).sourceType()).isEqualTo(SourceType.PARAMETER_TABLE);
        // within REQUIREMENT: factKey asc: aaa#key before zzz#key
        assertThat(result.candidates().get(1).factKey()).isEqualTo("aaa#key");
        assertThat(result.candidates().get(2).factKey()).isEqualTo("zzz#key");
    }

    // ── 冲突惩罚 ──────────────────────────────────────────────────────

    @Test
    void conflictedClaimsGetPenaltyDroppingRank() {
        UnifiedKnowledgeClaim normal = claim("c-1", SourceType.REQUIREMENT, "authn#login", "登录", Authority.PRIMARY, KnowledgeStatus.SUPPORTED);
        UnifiedKnowledgeClaim conflicted = claim("c-2", SourceType.REQUIREMENT, "authn#sso", "单点登录", Authority.PRIMARY, KnowledgeStatus.CONFLICTED);

        // Both have exact match on subject "登录" — wait, "单点登录" contains "登录"
        // Let's use query that matches both subjects
        FusionResult result = fusion.fuse(null, List.of(conflicted, normal), "登录");

        // normal should rank higher (no conflict penalty)
        assertThat(result.candidates().get(0).claimId()).isEqualTo("c-1");
        assertThat(result.candidates().get(1).claimId()).isEqualTo("c-2");
    }

    // ── 精确命中加分 ──────────────────────────────────────────────────

    @Test
    void exactMatchOnFactKeyBoostsRank() {
        UnifiedKnowledgeClaim noMatch = claim("c-1", SourceType.REQUIREMENT, "zzz#other", "其他", Authority.PRIMARY, KnowledgeStatus.SUPPORTED);
        UnifiedKnowledgeClaim exactMatch = claim("c-2", SourceType.REQUIREMENT, "authn#login", "登录", Authority.PRIMARY, KnowledgeStatus.SUPPORTED);

        // Query matches factKey of c-2 ("authn#login" contains "login")
        // But query is "authn" — matches factKey prefix
        FusionResult result = fusion.fuse(null, List.of(noMatch, exactMatch), "authn");

        // exactMatch should rank higher (exact boost on factKey)
        assertThat(result.candidates().get(0).claimId()).isEqualTo("c-2");
    }

    // ── 权重配置 ──────────────────────────────────────────────────────

    @Test
    void customWeightsAffectRanking() {
        UnifiedKnowledgeClaim vecClaim = claim("c-1", SourceType.REQUIREMENT, "authn#login", "登录", Authority.PRIMARY, KnowledgeStatus.SUPPORTED);
        UnifiedKnowledgeClaim directClaim = claim("c-2", SourceType.REQUIREMENT, "authn#sso", "单点登录", Authority.PRIMARY, KnowledgeStatus.SUPPORTED);

        Map<String, Double> scores = Map.of("c-1", 1.0);
        ScoredCandidateLoad vectorLoad = scoredLoad(List.of(vecClaim), scores);

        // With high vector weight (0.95), vector claim should rank higher
        double[] vecHeavyWeights = {0.95, 0.01, 0.02, 0.02};
        FusionResult result = fusion.fuse(vectorLoad, List.of(directClaim), "单点登录", vecHeavyWeights);

        assertThat(result.candidates().get(0).claimId()).isEqualTo("c-1");
        assertThat(result.fusionWeightsSnapshot()).contains("vec=0.95");
    }

    @Test
    void invalidWeightsThrow() {
        assertThatThrownBy(() -> fusion.fuse(null, List.of(), "q", new double[]{0.5}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weights must have exactly 4 elements");
    }

    // ── FusionResult 统计 ─────────────────────────────────────────────

    @Test
    void fusionResultStatisticsCorrect() {
        UnifiedKnowledgeClaim c1 = claim("c-1", SourceType.REQUIREMENT, "k1", "s1", Authority.PRIMARY, KnowledgeStatus.SUPPORTED);
        UnifiedKnowledgeClaim c2 = claim("c-2", SourceType.REQUIREMENT, "k2", "s2", Authority.PRIMARY, KnowledgeStatus.SUPPORTED);
        UnifiedKnowledgeClaim c3 = claim("c-3", SourceType.REQUIREMENT, "k3", "s3", Authority.PRIMARY, KnowledgeStatus.SUPPORTED);

        // c-1 in both, c-2 vector only, c-3 direct only
        Map<String, Double> scores = Map.of("c-1", 0.90, "c-2", 0.80);
        ScoredCandidateLoad vectorLoad = scoredLoad(List.of(c1, c2), scores);

        FusionResult result = fusion.fuse(vectorLoad, List.of(c1, c3), "q");

        assertThat(result.candidates()).hasSize(3); // c-1 deduped, c-2 + c-3 added
        assertThat(result.vectorHitCount()).isEqualTo(2);
        assertThat(result.directHitCount()).isEqualTo(2);
        assertThat(result.duplicateRemovedCount()).isEqualTo(1);
    }

    // ── ScoredCandidateLoad ───────────────────────────────────────────

    @Test
    void scoredCandidateLoadNullScoresDefaultsToEmpty() {
        CandidateLoad load = new CandidateLoad(List.of(), List.of(), List.of("gen-1"));
        ScoredCandidateLoad scored = new ScoredCandidateLoad(load, null);
        assertThat(scored.scores()).isEmpty();
        assertThat(scored.claims()).isEmpty();
        assertThat(scored.buildIds()).containsExactly("gen-1");
    }

    @Test
    void scoredCandidateLoadNullLoadThrows() {
        assertThatThrownBy(() -> new ScoredCandidateLoad(null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("load must not be null");
    }
}
