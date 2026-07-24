package com.example.requirementrag.model;

/** 可安全返回给调用方的 RAG 降级说明，不包含底层异常原文。 */
public record RagWarning(
        String stage,
        String code,
        String message,
        long durationMs
) {
}
