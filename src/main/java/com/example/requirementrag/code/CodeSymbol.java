package com.example.requirementrag.code;

/** 从仓库快照中提取的稳定静态符号（类、方法、函数等），以项目+commit+ID 唯一标识。 */
public record CodeSymbol(String id, String projectId, String commitSha, String language,
                         String kind, String qualifiedName, String simpleName, String filePath,
                         int startLine, int endLine, boolean entryPoint, boolean testSymbol) {
}
