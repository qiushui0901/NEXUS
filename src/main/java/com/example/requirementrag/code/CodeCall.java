package com.example.requirementrag.code;

/**
 * 语言解析器输出的未解析调用点（call-site）。
 * 仅记录被调用目标名与调用位置，后续由符号图谱存储按名称解析为具体调用关系。
 */
public record CodeCall(String id, String projectId, String commitSha, String language,
                       String callerSymbolId, String callerQualifiedName, String targetName,
                       String filePath, int line) {
}
