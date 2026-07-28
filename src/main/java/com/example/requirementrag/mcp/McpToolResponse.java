package com.example.requirementrag.mcp;

import com.example.requirementrag.model.RagWarning;

import java.util.List;

/** Shared, evidence-first response envelope for every NEXUS MCP tool. */
public record McpToolResponse<T>(
        ResolvedScope resolved,
        T data,
        List<?> evidence,
        Object quality,
        List<RagWarning> warnings,
        boolean truncated
) {
    public McpToolResponse {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public record ResolvedScope(String projectId, String version, String documentId) {
    }
}
