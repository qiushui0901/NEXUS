package com.example.requirementrag.requirement.graph;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** 需求语义图配置。默认关闭，避免图构建或图检索改变现有需求主链路。 */
@ConfigurationProperties("app.rag.requirement-graph")
public record RequirementGraphProperties(
        boolean enabled,
        boolean extractionEnabled,
        boolean retrievalEnabled,
        String databasePath,
        int maxEntitiesPerChunk,
        int maxRelationsPerChunk,
        int maxInputChars,
        int maxHops,
        int candidateLimit,
        String extractionModel,
        String extractionPromptVersion
) {
    @ConstructorBinding
    public RequirementGraphProperties {
        databasePath = databasePath == null || databasePath.isBlank()
                ? "data/requirement-graph.db" : databasePath.trim();
        maxEntitiesPerChunk = bounded(maxEntitiesPerChunk, 1, 100, 20);
        maxRelationsPerChunk = bounded(maxRelationsPerChunk, 1, 200, 30);
        maxInputChars = bounded(maxInputChars, 1_000, 200_000, 20_000);
        maxHops = bounded(maxHops, 0, 4, 2);
        candidateLimit = bounded(candidateLimit, 1, 200, 40);
        extractionModel = extractionModel == null || extractionModel.isBlank() ? null : extractionModel.trim();
        extractionPromptVersion = extractionPromptVersion == null || extractionPromptVersion.isBlank()
                ? "v1" : extractionPromptVersion.trim();
    }

    private static int bounded(int value, int min, int max, int fallback) {
        if (value == 0) return fallback;
        if (value < min || value > max) {
            throw new IllegalArgumentException("requirement graph setting out of range: " + value);
        }
        return value;
    }
}
