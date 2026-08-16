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

    public EvaluationCandidate transition(String candidateId, ReviewStatus target, String reviewer) {
        EvaluationCandidate current = require(candidateId);
        EnumSet<ReviewStatus> allowed = TRANSITIONS.getOrDefault(current.reviewStatus(), EnumSet.noneOf(ReviewStatus.class));
        if (!allowed.contains(target)) {
            throw new IllegalArgumentException("非法候选状态转换: " + current.reviewStatus() + " -> " + target);
        }
        EvaluationCandidate updated = new EvaluationCandidate(
                current.candidateId(), current.sourceExperienceId(), current.queryHash(),
                current.queryPreview(), current.failureType(), current.failureReason(),
                current.predictedRelevantIds(), current.priorityScore(), target,
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
        String datasetVersion = version == null || version.isBlank()
                ? "ds-" + Instant.now().toEpochMilli() : version.trim();
        EvaluationDataset previous = datasetRegistry.active();
        List<EvaluationCase> cases = approved.stream()
                .map(c -> new EvaluationCase(c.candidateId(), text(c.queryPreview(), c.queryHash()),
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

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
