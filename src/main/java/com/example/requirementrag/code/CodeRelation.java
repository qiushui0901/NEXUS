package com.example.requirementrag.code;

/**
 * 一条静态调用关系：已解析（EXACT/SAME_FILE/HEURISTIC）或未解析（UNRESOLVED）。
 * evidence 记录解析依据（即解析置信度的枚举名）。
 */
public record CodeRelation(String id, String projectId, String commitSha, String callerSymbolId,
                           String calleeSymbolId, String targetName, String filePath, int line,
                           Resolution resolution, String evidence) {
    /** 调用解析置信度：EXACT=全限定名精确匹配，SAME_FILE=同文件简单名匹配，HEURISTIC=启发式匹配，UNRESOLVED=未解析。 */
    public enum Resolution {
        EXACT, SAME_FILE, HEURISTIC, UNRESOLVED
    }
}
