package com.example.requirementrag.model;

/**
 * Git 范围增量代码索引的结果摘要，不包含代码内容或向量数据。
 */
public record IncrementalCodeIndexResponse(
        String projectId,
        String oldCommit,
        String newCommit,
        int changedFiles,
        int javaFiles,
        int indexedChunks
) {
}
