package com.example.requirementrag.model;

import java.util.List;
import java.util.Map;

/**
 * RAG 链路运行快照。
 */
public record RagChainSnapshot(
        List<String> expectedStages,
        List<RagStageEvent> recentStages,
        Map<String, Double> tokenUsage,
        Map<String, Double> toolMetrics
) {
}
