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
        String contentHash,
        String language
) {
    /** Backward-compatible constructor for pre-0.7 callers and stored payloads. */
    public CodeChunk(String id, String projectId, String commitSha, String filePath,
                     String symbolType, String symbolName, int startLine, int endLine,
                     String text, String contentHash) {
        this(id, projectId, commitSha, filePath, symbolType, symbolName, startLine, endLine,
                text, contentHash, com.example.requirementrag.code.CodeLanguage.fromPath(filePath).id());
    }
}
