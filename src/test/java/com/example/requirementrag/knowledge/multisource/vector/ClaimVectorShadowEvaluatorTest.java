package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import com.example.requirementrag.knowledge.multisource.vector.ClaimVectorShadowEvaluator.QueryRecord;
import com.example.requirementrag.knowledge.multisource.vector.ClaimVectorShadowEvaluator.ScopeStats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimVectorShadowEvaluatorTest {

    private final ClaimVectorShadowEvaluator evaluator = new ClaimVectorShadowEvaluator();

    private UnifiedKnowledgeClaim claim(String id) {
        return new UnifiedKnowledgeClaim(
                id, "proj-1", "v1", "factKey", "subject", "pred", "val",
                "TEXT", "", SourceType.REQUIREMENT, Authority.PRIMARY,
                KnowledgeStatus.SUPPORTED, null, null, "doc-1", "module", null);
    }

    // ── 空结果 ────────────────────────────────────────────────────────

    @Test
    void recordQueryWithEmptyResults() {
        evaluator.recordQuery("proj-1", "v1", "查询", List.of(), List.of(), 50);

        ScopeStats stats = evaluator.scopeMetric("proj-1", "v1");
        assertThat(stats).isNotNull();
        assertThat(stats.queryCount()).isEqualTo(1);
        assertThat(stats.totalVectorHits()).isZero();
        assertThat(stats.totalDirectHits()).isZero();
        assertThat(stats.totalOverlap()).isZero();
        assertThat(stats.queriesWithVectorOnlyHits()).isZero();
        assertThat(stats.totalElapsedMs()).isEqualTo(50);
    }

    // ── 非空结果 ──────────────────────────────────────────────────────

    @Test
    void recordQueryWithNonEmptyResults() {
        var vecCandidates = List.of(claim("c-1"), claim("c-2"));
        var directCandidates = List.of(claim("c-2"), claim("c-3"));

        evaluator.recordQuery("proj-1", "v1", "查询", vecCandidates, directCandidates, 100);

        ScopeStats stats = evaluator.scopeMetric("proj-1", "v1");
        assertThat(stats.queryCount()).isEqualTo(1);
        assertThat(stats.totalVectorHits()).isEqualTo(2);
        assertThat(stats.totalDirectHits()).isEqualTo(2);
        assertThat(stats.totalOverlap()).isEqualTo(1); // c-2 in both
        assertThat(stats.queriesWithVectorOnlyHits()).isEqualTo(1); // c-1 is vector-only
        assertThat(stats.avgVectorHitsPerQuery()).isEqualTo(2.0);
        assertThat(stats.avgDirectHitsPerQuery()).isEqualTo(2.0);
        assertThat(stats.vectorRecallContributionRate()).isEqualTo(1.0);
    }

    // ── scope 隔离 ────────────────────────────────────────────────────

    @Test
    void differentScopesAreIsolated() {
        evaluator.recordQuery("proj-1", "v1", "q1",
                List.of(claim("c-1")), List.of(), 50);
        evaluator.recordQuery("proj-2", "v2", "q2",
                List.of(), List.of(claim("c-2")), 30);

        ScopeStats stats1 = evaluator.scopeMetric("proj-1", "v1");
        ScopeStats stats2 = evaluator.scopeMetric("proj-2", "v2");

        assertThat(stats1.queryCount()).isEqualTo(1);
        assertThat(stats1.totalVectorHits()).isEqualTo(1);
        assertThat(stats2.queryCount()).isEqualTo(1);
        assertThat(stats2.totalDirectHits()).isEqualTo(1);
    }

    // ── 多查询聚合 ────────────────────────────────────────────────────

    @Test
    void multipleQueriesAggregateIntoScopeStats() {
        // Query 1: vector adds recall
        evaluator.recordQuery("proj-1", "v1", "q1",
                List.of(claim("c-1")), List.of(), 50);
        // Query 2: vector doesn't add recall
        evaluator.recordQuery("proj-1", "v1", "q2",
                List.of(claim("c-2")), List.of(claim("c-2")), 30);

        ScopeStats stats = evaluator.scopeMetric("proj-1", "v1");
        assertThat(stats.queryCount()).isEqualTo(2);
        assertThat(stats.totalVectorHits()).isEqualTo(2);
        assertThat(stats.totalDirectHits()).isEqualTo(1);
        assertThat(stats.totalOverlap()).isEqualTo(1);
        assertThat(stats.queriesWithVectorOnlyHits()).isEqualTo(1); // only q1
        assertThat(stats.totalElapsedMs()).isEqualTo(80);
        assertThat(stats.avgElapsedMs()).isEqualTo(40);
    }

    // ── publishIfReady ────────────────────────────────────────────────

    @Test
    void publishIfReadyFalseWhenFewerThan20Queries() {
        for (int i = 0; i < 19; i++) {
            evaluator.recordQuery("proj-1", "v1", "q" + i,
                    List.of(claim("c-" + i)), List.of(), 50);
        }
        assertThat(evaluator.publishIfReady("proj-1", "v1")).isFalse();
    }

    @Test
    void publishIfReadyFalseWhenRecallRateBelow30Percent() {
        // 20 queries, but only 5 (25%) have vector-only hits
        for (int i = 0; i < 20; i++) {
            if (i < 5) {
                // vector adds recall
                evaluator.recordQuery("proj-1", "v1", "q" + i,
                        List.of(claim("c-" + i)), List.of(), 50);
            } else {
                // no vector-only hits
                evaluator.recordQuery("proj-1", "v1", "q" + i,
                        List.of(claim("c-" + i)), List.of(claim("c-" + i)), 50);
            }
        }
        assertThat(evaluator.publishIfReady("proj-1", "v1")).isFalse();
    }

    @Test
    void publishIfReadyTrueWhen20QueriesAnd30PercentRecall() {
        // 20 queries, 7 (35%) have vector-only hits
        for (int i = 0; i < 20; i++) {
            if (i < 7) {
                evaluator.recordQuery("proj-1", "v1", "q" + i,
                        List.of(claim("c-" + i)), List.of(), 50);
            } else {
                evaluator.recordQuery("proj-1", "v1", "q" + i,
                        List.of(claim("c-" + i)), List.of(claim("c-" + i)), 50);
            }
        }
        assertThat(evaluator.publishIfReady("proj-1", "v1")).isTrue();
    }

    @Test
    void publishIfReadyFalseWhenNoData() {
        assertThat(evaluator.publishIfReady("proj-1", "v1")).isFalse();
    }

    // ── recentQueries ─────────────────────────────────────────────────

    @Test
    void recentQueriesReturnsCopy() {
        evaluator.recordQuery("proj-1", "v1", "q1",
                List.of(claim("c-1")), List.of(), 50);

        List<QueryRecord> records = evaluator.recentQueries("proj-1", "v1");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).query()).isEqualTo("q1");
        assertThat(records.get(0).vectorHitCount()).isEqualTo(1);
        assertThat(records.get(0).vectorAddsRecall()).isTrue();

        // Modifying returned list doesn't affect internal state
        assertThat(records).isNotSameAs(evaluator.recentQueries("proj-1", "v1"));
    }

    @Test
    void recentQueriesCapsAt100() {
        for (int i = 0; i < 120; i++) {
            evaluator.recordQuery("proj-1", "v1", "q" + i,
                    List.of(claim("c-" + i)), List.of(), 10);
        }
        List<QueryRecord> records = evaluator.recentQueries("proj-1", "v1");
        assertThat(records).hasSize(100);
        // Should keep the most recent 100 (q20 through q119)
        assertThat(records.get(0).query()).isEqualTo("q20");
        assertThat(records.get(99).query()).isEqualTo("q119");
    }

    @Test
    void recentQueriesEmptyWhenNoData() {
        assertThat(evaluator.recentQueries("proj-1", "v1")).isEmpty();
    }
}
