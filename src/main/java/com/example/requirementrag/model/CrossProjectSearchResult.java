package com.example.requirementrag.model;

/** 跨项目需求检索单条结果，含来源项目与相关性分数。 */
public record CrossProjectSearchResult(
        String projectId,
        String projectName,
        ChunkRecord chunkRecord,
        double score
) {
}
