package com.example.requirementrag.model;

/** 单条需求存疑，含模块、功能点、问题、类型与状态。 */
public record RequirementDoubt(
        String module,
        String feature,
        String question,
        DoubtType type,
        DoubtStatus status,
        String sourceLocation
) {
}
