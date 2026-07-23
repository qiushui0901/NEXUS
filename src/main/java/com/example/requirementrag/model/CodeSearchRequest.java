package com.example.requirementrag.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 代码语义检索请求。
 */
public record CodeSearchRequest(@NotBlank String query, String projectId, Integer limit) {
}
