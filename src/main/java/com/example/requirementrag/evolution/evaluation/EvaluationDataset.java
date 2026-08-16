package com.example.requirementrag.evolution.evaluation;

import java.time.Instant;
import java.util.List;

/** 不可变评测数据集版本。 */
public record EvaluationDataset(
        String version,
        List<EvaluationCase> cases,
        Instant createdAt,
        String previousVersion
) {
    public EvaluationDataset {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
