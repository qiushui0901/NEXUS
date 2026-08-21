package com.example.requirementrag.requirement.graph;

/** Deterministic quality gate for the synthetic requirement-graph corpus. */
public final class RequirementGraphQualityGate {
    private RequirementGraphQualityGate() {
    }

    public static void assertPass(Report report) {
        if (report == null) throw new AssertionError("需求图评测报告为空");
        if (report.entityPrecision() < 0.85) throw new AssertionError("实体 precision 未达标");
        if (report.relationPrecision() < 0.80) throw new AssertionError("关系 precision 未达标");
        if (report.evidenceSpanValidity() < 0.98) throw new AssertionError("证据跨度有效率未达标");
        if (report.unsupportedPublishedClaimRate() > 0) throw new AssertionError("存在未受支持的已发布声明");
        if (report.publishedUnresolvedEvidenceCount() > 0) throw new AssertionError("存在已发布未解析证据");
        if (report.resumeDuplicateCallRate() > 0.01) throw new AssertionError("恢复重复调用率未达标");
        if (report.retryableRecoveryRate() < 0.95) throw new AssertionError("可重试失败恢复率未达标");
    }

    public record Report(
            double entityPrecision,
            double relationPrecision,
            double evidenceSpanValidity,
            double unsupportedPublishedClaimRate,
            int publishedUnresolvedEvidenceCount,
            double resumeDuplicateCallRate,
            double retryableRecoveryRate
    ) {
    }
}
