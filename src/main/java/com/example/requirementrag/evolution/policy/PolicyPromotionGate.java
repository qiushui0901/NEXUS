package com.example.requirementrag.evolution.policy;

import com.example.requirementrag.evolution.evaluation.ExperimentReport;
import org.springframework.stereotype.Service;

/**
 * 离线质量门禁：候选策略必须满足总体不回退 + 关键指标门禁。
 * <p>
 * 首期使用保守阈值：Recall@1 不回退、Recall@10 不回退、nDCG 回退不超过 0.5pp、
 * P95 增幅不超过 10%。
 * </p>
 */
@Service
public class PolicyPromotionGate {

    private static final double NDCG_MAX_REGRESSION = 0.005;
    private static final double P95_MAX_INCREASE = 0.10;

    public boolean passes(ExperimentReport report) {
        if (report == null || report.baseline() == null || report.candidate() == null) {
            return false;
        }
        ExperimentReport.MetricSummary baseline = report.baseline();
        ExperimentReport.MetricSummary candidate = report.candidate();
        return candidate.recallAt1() >= baseline.recallAt1()
                && candidate.recallAt10() >= baseline.recallAt10()
                && candidate.ndcgAt10() >= baseline.ndcgAt10() - NDCG_MAX_REGRESSION
                && candidate.p95Ms() <= baseline.p95Ms() * (1.0 + P95_MAX_INCREASE);
    }
}
