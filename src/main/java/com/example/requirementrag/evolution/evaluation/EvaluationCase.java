package com.example.requirementrag.evolution.evaluation;

import java.util.List;

/** 评测数据集中的单条样本。 */
public record EvaluationCase(
        String caseId,
        String query,
        String projectId,
        String version,
        List<String> relevantIds
) {
    public EvaluationCase {
        relevantIds = relevantIds == null ? List.of() : List.copyOf(relevantIds);
    }
}
