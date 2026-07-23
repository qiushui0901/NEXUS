package com.example.requirementrag.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 面向开发入手问题的分析请求。
 */
public record DevelopmentPlanRequest(
        @NotBlank String query,
        String documentId,
        String version,
        String projectId,
        Integer limit
) {
}
