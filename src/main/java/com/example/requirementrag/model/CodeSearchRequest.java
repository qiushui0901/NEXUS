package com.example.requirementrag.model;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 代码语义检索请求。
 */
public record CodeSearchRequest(@NotBlank String query, String projectId, Integer limit,
                                List<String> repositoryIds) {
    public CodeSearchRequest {
        repositoryIds = repositoryIds == null ? List.of() : List.copyOf(repositoryIds);
    }

    public CodeSearchRequest(String query, String projectId, Integer limit) {
        this(query, projectId, limit, List.of());
    }
}
