package com.example.requirementrag.code;

/** A stable static symbol extracted from a repository snapshot. */
public record CodeSymbol(String id, String projectId, String commitSha, String language,
                         String kind, String qualifiedName, String simpleName, String filePath,
                         int startLine, int endLine, boolean entryPoint, boolean testSymbol) {
}
