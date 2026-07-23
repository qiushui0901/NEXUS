package com.example.requirementrag.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 代码图谱请求。
 */
public record CodeGraphRequest(@NotBlank String query, String projectId, String view, Integer limit, Boolean crossSide) {
    public CodeGraphRequest {
        if (crossSide == null) {
            crossSide = false;
        }
    }
}
