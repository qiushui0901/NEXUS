package com.example.requirementrag.evolution.evaluation;

import java.time.Instant;

/** 实验运行清单：固定输入版本与策略版本，用于复现。 */
public record ExperimentManifest(
        String experimentId,
        String baselinePolicyVersion,
        String candidatePolicyVersion,
        String datasetVersion,
        String indexVersion,
        String modelVersion,
        long randomSeed,
        int repetitions,
        Instant createdAt
) {
}
