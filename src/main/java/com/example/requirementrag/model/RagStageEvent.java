package com.example.requirementrag.model;

import java.time.Instant;

/**
 * 最近一次 RAG 阶段运行事件。
 */
public record RagStageEvent(
        Instant at,
        String stage,
        String documentId,
        String version,
        String status,
        long durationMs,
        String error
) {
}
