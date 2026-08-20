package com.example.requirementrag.model;

import jakarta.validation.constraints.NotBlank;

/** 静态符号图遍历请求模型：按符号名在指定方向（direction）上做有深度/数量上限的遍历。 */
public record SymbolGraphRequest(String projectId, String repositoryId, @NotBlank String symbol, String direction,
                                 Integer depth, Integer limit) {
    /** 兼容旧构造器。 */
    public SymbolGraphRequest(String projectId, String symbol, String direction, Integer depth, Integer limit) {
        this(projectId, null, symbol, direction, depth, limit);
    }
}
