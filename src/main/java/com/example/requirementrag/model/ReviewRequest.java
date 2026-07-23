package com.example.requirementrag.model;

import jakarta.validation.constraints.NotBlank;

/** 评审请求参数：文档 ID、版本、可选模块过滤与可选项目 ID。 */
public record ReviewRequest(
        @NotBlank String documentId,
        @NotBlank String version,
        String module,
        String projectId
) {
}
