package com.example.requirementrag.model;

import jakarta.validation.constraints.NotBlank;

/** 跨项目需求检索请求。 */
public record CrossProjectSearchRequest(@NotBlank String query, Integer topK) {
}
