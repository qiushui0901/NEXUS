package com.example.requirementrag.evolution.evaluation;

import java.util.List;

/** 一次策略实验的完整报告。 */
public record ExperimentReport(
        ExperimentManifest manifest,
        List<CaseResult> cases,
        MetricSummary baseline,
        MetricSummary candidate,
        boolean passedGate
) {
    public ExperimentReport {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    /** 单个评测样本的结果。 */
    public record CaseResult(
            String caseId,
            String query,
            List<String> predictedIds,
            List<String> relevantIds,
            int rankOfFirstRelevant,
            boolean recallAt1,
            boolean recallAt5,
            boolean recallAt10,
            long latencyMs,
            String status
    ) {
        public CaseResult {
            predictedIds = predictedIds == null ? List.of() : List.copyOf(predictedIds);
            relevantIds = relevantIds == null ? List.of() : List.copyOf(relevantIds);
        }
    }

    /** 聚合指标摘要。 */
    public record MetricSummary(
            double recallAt1,
            double recallAt5,
            double recallAt10,
            double mrrAt10,
            double ndcgAt10,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double failedRate,
            double degradedRate,
            double supplementRate
    ) {
    }
}
