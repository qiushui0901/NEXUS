package com.example.requirementrag.evolution.mining;

/** 失败样本的稳定分类。 */
public enum FailureType {
    NO_HIT,
    TOP1_MISMATCH,
    LOW_RANK,
    DUPLICATE_ONLY,
    SINGLE_SIDE_ONLY,
    CORE_STAGE_FAILED,
    USER_REJECTED,
    HIGH_LATENCY,
    DEGRADED_RESULT,
    INDEX_STALENESS
}
