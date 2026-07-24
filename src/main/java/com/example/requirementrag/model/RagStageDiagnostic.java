package com.example.requirementrag.model;

/** 单个 RAG 阶段的状态、耗时和产出数量。 */
public record RagStageDiagnostic(
        String stage,
        RagOutcomeStatus status,
        long durationMs,
        long itemCount
) {
}
