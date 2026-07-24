package com.example.requirementrag.model;

/** 统一描述 RAG 阶段或请求的结果语义。 */
public enum RagOutcomeStatus {
    SUCCESS,
    NO_RESULTS,
    DEGRADED,
    FAILED
}
