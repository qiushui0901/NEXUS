package com.example.requirementrag.evolution.mining;

import java.time.Instant;
import java.util.List;

/**
 * 从线上经验中挖掘出的待审核评测样本候选。
 * <p>
 * 候选不能直接进入正式 golden set；必须经过人工审核并发布为不可变数据集版本。
 * </p>
 *
 * @param candidateId          候选 ID
 * @param sourceExperienceId   来源经验 ID
 * @param queryHash            查询哈希
 * @param queryPreview         查询预览（可能为空）
 * @param failureType          失败类型
 * @param failureReason        人类可读的失败原因
 * @param indexVersion         来源索引版本，用于去重
 * @param predictedRelevantIds 人工确认后的相关 ID；未被审核前应为空
 * @param priorityScore        确定性优先级分数
 * @param reviewStatus         审核状态
 * @param reviewer             审核人
 * @param reviewedAt           审核时间
 */
public record EvaluationCandidate(
        String candidateId,
        String sourceExperienceId,
        String queryHash,
        String queryPreview,
        FailureType failureType,
        String failureReason,
        String indexVersion,
        List<String> predictedRelevantIds,
        double priorityScore,
        ReviewStatus reviewStatus,
        String reviewer,
        Instant reviewedAt
) {
    public EvaluationCandidate {
        indexVersion = indexVersion == null || indexVersion.isBlank() ? "unknown" : indexVersion;
        predictedRelevantIds = predictedRelevantIds == null ? List.of() : List.copyOf(predictedRelevantIds);
        reviewStatus = reviewStatus == null ? ReviewStatus.DRAFT : reviewStatus;
    }
}
