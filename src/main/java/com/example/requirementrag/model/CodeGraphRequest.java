package com.example.requirementrag.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 代码图谱查询请求：以自然语言查询代码结构、依赖与影响关系，可指定视图、数量限制及是否跨端（side）。
 */
public record CodeGraphRequest(@NotBlank String query, String projectId, String view, Integer limit, Boolean crossSide) {
    /** 规范化构造：crossSide 未指定时默认 false。 */
    public CodeGraphRequest {
        if (crossSide == null) {
            crossSide = false;
        }
    }
}
