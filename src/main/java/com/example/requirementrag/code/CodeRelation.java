package com.example.requirementrag.code;

/** Resolved or visible unresolved static call relation. */
public record CodeRelation(String id, String projectId, String commitSha, String callerSymbolId,
                           String calleeSymbolId, String targetName, String filePath, int line,
                           Resolution resolution, String evidence) {
    public enum Resolution {
        EXACT, SAME_FILE, HEURISTIC, UNRESOLVED
    }
}
