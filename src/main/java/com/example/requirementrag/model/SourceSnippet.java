package com.example.requirementrag.model;

/**
 * 源码片段响应。
 */
public record SourceSnippet(String filePath, int startLine, int endLine, String text) {
}
