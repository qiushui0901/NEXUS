package com.example.requirementrag.requirement.semantic;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** 需求语义 Chunk 增强的构建、预算与灰度开关（默认全部关闭）。 */
@ConfigurationProperties("app.rag.requirement-semantic")
public record RequirementSemanticProperties(
        boolean enabled,
        boolean candidateRetrievalEnabled,
        boolean normativeRetrievalEnabled,
        boolean vectorIndexEnabled,
        String databasePath,
        String model,
        String promptVersion,
        String schemaVersion,
        int maxInputChars,
        int maxEntitiesPerChunk,
        int maxConditionsPerChunk,
        int maxEventsPerChunk,
        int maxNumericFactsPerChunk,
        int maxQuestionsPerChunk,
        int maxClaimsPerChunk,
        int maxRetries,
        int maxModelCalls,
        int maxWallClockSeconds,
        int maxEstimatedTokens,
        int windowOverlapChars,
        boolean allowInferredCandidate,
        int maxCandidateAnnotations
) {
    @ConstructorBinding
    public RequirementSemanticProperties {
        databasePath = databasePath == null || databasePath.isBlank()
                ? "data/requirement-semantic.db" : databasePath.trim();
        model = model == null || model.isBlank() ? null : model.trim();
        promptVersion = textOr(promptVersion, "requirement-semantic-v1");
        schemaVersion = textOr(schemaVersion, "v1");
        maxInputChars = bounded(maxInputChars, 1_000, 200_000, 12_000);
        maxEntitiesPerChunk = bounded(maxEntitiesPerChunk, 1, 100, 30);
        maxConditionsPerChunk = bounded(maxConditionsPerChunk, 1, 100, 30);
        maxEventsPerChunk = bounded(maxEventsPerChunk, 1, 100, 30);
        maxNumericFactsPerChunk = bounded(maxNumericFactsPerChunk, 1, 100, 30);
        maxQuestionsPerChunk = bounded(maxQuestionsPerChunk, 1, 100, 20);
        maxClaimsPerChunk = bounded(maxClaimsPerChunk, 1, 100, 30);
        maxRetries = bounded(maxRetries, 0, 10, 2);
        maxModelCalls = bounded(maxModelCalls, 1, 20_000, 1_000);
        maxWallClockSeconds = bounded(maxWallClockSeconds, 1, 86_400, 1_800);
        maxEstimatedTokens = bounded(maxEstimatedTokens, 0, 100_000_000, 1_000_000);
        windowOverlapChars = bounded(windowOverlapChars, 0, 20_000, 400);
        // 中（第七批 Review M5）：上限 20000 与检索路径 SQL 侧硬性 limit 封顶一致——
        // 100k 配置会让候选加载一次物化数万行 result_json（每行约 12k 字符），瞬时堆可达数百 MB。
        maxCandidateAnnotations = bounded(maxCandidateAnnotations, 100, 20_000, 5_000);
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int bounded(int value, int min, int max, int fallback) {
        if (value == 0) return fallback;
        if (value < min || value > max) {
            throw new IllegalArgumentException("requirement semantic setting out of range: " + value);
        }
        return value;
    }
}
