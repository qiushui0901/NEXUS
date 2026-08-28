package com.example.requirementrag.knowledge.multisource.entity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** 实体提取与归一化（Phase 2）配置：规则路径始终可用，LLM 辅助可按需关闭。 */
@ConfigurationProperties("app.rag.entity-extraction")
public record EntityExtractionProperties(
        boolean enabled,
        String model,
        int maxMentionsPerQuery,
        int maxAliasScan,
        int sourceBatchSize,
        int maxEntitiesPerSourceBatch,
        int maxFactsPerSourceBatch,
        int maxRelationsPerSourceBatch,
        double reviewThreshold,
        boolean allowLlmAssist,
        int maxRetries
) {
    @ConstructorBinding
    public EntityExtractionProperties {
        model = model == null || model.isBlank() ? null : model.trim();
        maxMentionsPerQuery = bounded(maxMentionsPerQuery, 1, 50, 8);
        maxAliasScan = bounded(maxAliasScan, 100, 500_000, 50_000);
        sourceBatchSize = bounded(sourceBatchSize, 1, 5_000, 200);
        maxEntitiesPerSourceBatch = bounded(maxEntitiesPerSourceBatch, 1, 200, 50);
        maxFactsPerSourceBatch = bounded(maxFactsPerSourceBatch, 1, 500, 100);
        maxRelationsPerSourceBatch = bounded(maxRelationsPerSourceBatch, 1, 500, 100);
        reviewThreshold = boundThreshold(reviewThreshold);
        maxRetries = bounded(maxRetries, 0, 5, 1);
    }

    private static int bounded(int value, int min, int max, int fallback) {
        if (value <= 0) return fallback;
        return Math.max(min, Math.min(max, value));
    }

    private static double boundThreshold(double value) {
        if (value <= 0) return 0.7;
        return Math.max(0.5, Math.min(0.95, value));
    }
}