package com.example.requirementrag.mcp;

import com.example.requirementrag.model.RagWarning;

import java.util.List;

/**
 * 所有 NEXUS MCP 工具共用的响应信封：以证据优先（evidence-first）为设计原则，
 * 携带解析后的作用域、业务数据、证据列表、质量信息与警告；{@code truncated} 标记结果是否被截断。
 *
 * @param <T> 业务 data 的类型
 */
public record McpToolResponse<T>(
        ResolvedScope resolved,
        T data,
        List<?> evidence,
        Object quality,
        List<RagWarning> warnings,
        boolean truncated
) {
    /** 紧凑构造器：null 的 evidence/warnings 规范为空列表并做不可变拷贝。 */
    public McpToolResponse {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 调用解析后确定的作用域：生效项目 ID、版本与需求文档 ID。 */
    public record ResolvedScope(String projectId, String version, String documentId) {
    }
}
