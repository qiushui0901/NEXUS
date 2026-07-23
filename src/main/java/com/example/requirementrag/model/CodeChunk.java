package com.example.requirementrag.model;

/**
 * 可写入向量库的代码片段。
 */
public record CodeChunk(
        String id,
        String projectId,
        String commitSha,
        String filePath,
        String symbolType,
        String symbolName,
        int startLine,
        int endLine,
        String text,
        String contentHash
) {
}
