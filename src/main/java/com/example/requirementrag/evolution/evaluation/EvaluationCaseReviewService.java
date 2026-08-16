package com.example.requirementrag.evolution.evaluation;

import com.example.requirementrag.evolution.mining.EvaluationCandidate;
import com.example.requirementrag.evolution.mining.EvaluationCandidateStore;
import com.example.requirementrag.evolution.mining.ReviewStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/** 评测候选审核与数据集发布服务。 */
@Service
public class EvaluationCaseReviewService {

    private static final Map<ReviewStatus, EnumSet<ReviewStatus>> TRANSITIONS = Map.of(
            ReviewStatus.DRAFT, EnumSet.of(ReviewStatus.IN_REVIEW, ReviewStatus.REJECTED),
            ReviewStatus.IN_REVIEW, EnumSet.of(ReviewStatus.APPROVED, ReviewStatus.REJECTED),
            ReviewStatus.APPROVED, EnumSet.of(ReviewStatus.PUBLISHED),
            ReviewStatus.REJECTED, EnumSet.of(ReviewStatus.IN_REVIEW),
            ReviewStatus.PUBLISHED, EnumSet.of(ReviewStatus.ROLLED_BACK)
    );

    private final EvaluationCandidateStore candidateStore;
    private final EvaluationDatasetRegistry datasetRegistry;

    public EvaluationCaseReviewService(EvaluationCandidateStore candidateStore,
                                       EvaluationDatasetRegistry datasetRegistry) {
        this.candidateStore = candidateStore;
        this.datasetRegistry = datasetRegistry;
    }

    public List<EvaluationCandidate> listCandidates() {
        return candidateStore.findAll();
    }

    /** 仅做状态转换，不修改候选内容。 */
    public EvaluationCandidate transition(String candidateId, ReviewStatus target, String reviewer) {
        return updateAndTransition(candidateId, target, null, null, reviewer);
    }

    /**
     * 审核并允许人工修正候选内容。
     *
     * @param relevantIds 人工确认的相关 ID；传 null 表示不修改
     * @param queryPreview 人工修正的查询原文；传 null 表示不修改
     */
    public EvaluationCandidate updateAndTransition(String candidateId, ReviewStatus target,
                                                   List<String> relevantIds, String queryPreview,
                                                   String reviewer) {
        EvaluationCandidate current = require(candidateId);
        EnumSet<ReviewStatus> allowed = TRANSITIONS.getOrDefault(current.reviewStatus(), EnumSet.noneOf(ReviewStatus.class));
        if (!allowed.contains(target)) {
            throw new IllegalArgumentException("非法候选状态转换: " + current.reviewStatus() + " -> " + target);
        }
        List<String> resolvedRelevantIds = relevantIds == null
                ? current.predictedRelevantIds() : List.copyOf(relevantIds);
        String resolvedQueryPreview = queryPreview == null || queryPreview.isBlank()
                ? current.queryPreview() : queryPreview.trim();
        if (target == ReviewStatus.APPROVED) {
            if (resolvedQueryPreview == null || resolvedQueryPreview.isBlank()) {
                throw new IllegalArgumentException("APPROVED 候选必须包含真实查询文本");
            }
            if (resolvedRelevantIds.isEmpty()) {
                throw new IllegalArgumentException("APPROVED 候选必须包含人工确认的 relevant IDs");
            }
        }
        EvaluationCandidate updated = new EvaluationCandidate(
                current.candidateId(), current.sourceExperienceId(), current.queryHash(),
                resolvedQueryPreview, current.failureType(), current.failureReason(),
                current.indexVersion(), resolvedRelevantIds, current.priorityScore(), target,
                reviewer == null || reviewer.isBlank() ? "system" : reviewer, Instant.now());
        candidateStore.save(updated);
        return updated;
    }

    /** 将所有 APPROVED 候选发布为一个新的不可变数据集版本。 */
    public EvaluationDataset publishApproved(String version) {
        List<EvaluationCandidate> approved = candidateStore.findAll().stream()
                .filter(c -> c.reviewStatus() == ReviewStatus.APPROVED)
                .toList();
        if (approved.isEmpty()) {
            throw new IllegalArgumentException("没有 APPROVED 候选可发布");
        }
        for (EvaluationCandidate candidate : approved) {
            if (candidate.queryPreview() == null || candidate.queryPreview().isBlank()) {
                throw new IllegalArgumentException("候选 " + candidate.candidateId() + " 缺少真实查询文本，不能发布");
            }
            if (candidate.predictedRelevantIds().isEmpty()) {
                throw new IllegalArgumentException("候选 " + candidate.candidateId() + " 缺少人工确认的 relevant IDs，不能发布");
            }
        }
        String datasetVersion = version == null || version.isBlank()
                ? "ds-" + Instant.now().toEpochMilli() : version.trim();
        EvaluationDataset previous = datasetRegistry.active();
        List<EvaluationCase> cases = approved.stream()
                .map(c -> new EvaluationCase(c.candidateId(), c.queryPreview(),
                        null, null, c.predictedRelevantIds()))
                .toList();
        EvaluationDataset dataset = new EvaluationDataset(datasetVersion, cases, Instant.now(),
                previous == null ? null : previous.version());
        datasetRegistry.publish(dataset);
        for (EvaluationCandidate candidate : approved) {
            transition(candidate.candidateId(), ReviewStatus.PUBLISHED, "system");
        }
        return dataset;
    }

    public EvaluationDataset rollbackDataset(String version) {
        return datasetRegistry.rollback(version);
    }

    private EvaluationCandidate require(String candidateId) {
        EvaluationCandidate candidate = candidateStore.findById(candidateId);
        if (candidate == null) {
            throw new IllegalArgumentException("评测候选不存在: " + candidateId);
        }
        return candidate;
    }
}
