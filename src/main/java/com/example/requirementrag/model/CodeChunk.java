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
    /**
     * 兼容旧构造器：供 0.7 版本之前的调用方及已存储载荷使用，
     * 未指定语言时按文件路径推断 {@link com.example.requirementrag.code.CodeLanguage}。
     */
    public CodeChunk(String id, String projectId, String commitSha, String filePath,
                     String symbolType, String symbolName, int startLine, int endLine,
                     String text, String contentHash) {
        this(id, projectId, commitSha, filePath, symbolType, symbolName, startLine, endLine,
                text, contentHash, com.example.requirementrag.code.CodeLanguage.fromPath(filePath).id());
    }
}
