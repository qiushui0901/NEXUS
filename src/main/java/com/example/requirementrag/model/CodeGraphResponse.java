package com.example.requirementrag.model;

import java.util.List;

/**
 * 前端代码图谱响应。
 */
public record CodeGraphResponse(
        String query,
        String view,
        String intent,
        String summary,
        List<CodeGraphLayer> layers,
        List<CodeGraphTourStep> tour,
        List<CodeGraphNode> nodes,
        List<CodeGraphEdge> edges,
        List<CodeChunk> hits
) {
    /** 图节点。 */
    public record CodeGraphNode(String id, String type, String label, String filePath, Integer startLine, Integer endLine,
                                String role, String layer, String relevance, String projectId, String side) {
    }

    /** 图边。 */
    public record CodeGraphEdge(String source, String target, String type, String label) {
    }

    /** 图谱分层，用于前端按架构/业务角色自适应布局。 */
    public record CodeGraphLayer(String id, String name, String description, List<String> nodeIds) {
    }

    /** 引导式阅读步骤，用于告诉用户先看哪几个节点。 */
    public record CodeGraphTourStep(int order, String title, String description, List<String> nodeIds) {
    }
}
