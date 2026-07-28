package com.example.requirementrag.code;

/** An unresolved call-site emitted by a language parser. */
public record CodeCall(String id, String projectId, String commitSha, String language,
                       String callerSymbolId, String callerQualifiedName, String targetName,
                       String filePath, int line) {
}
