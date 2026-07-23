package com.example.requirementrag.model;

import java.util.Map;

/** 系统监控快照，聚合各组件健康状态与知识库统计。 */
public record MonitorSnapshot(
        String application,
        String qdrant,
        String ollama,
        BootstrapStatus bootstrap,
        KnowledgeStats knowledge,
        Map<String, Double> metrics
) {
    /** 知识库统计：文档、版本、分块数及导入文件数。 */
    public record KnowledgeStats(String documentId, String version, long chunkCount, long zipFiles, long xlsxRows) {
    }
}
