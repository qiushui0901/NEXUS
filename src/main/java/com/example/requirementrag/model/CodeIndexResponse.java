package com.example.requirementrag.model;

/**
 * 代码索引结果。
 */
public record CodeIndexResponse(String projectId, String commitSha, int files, int chunks) {
}
