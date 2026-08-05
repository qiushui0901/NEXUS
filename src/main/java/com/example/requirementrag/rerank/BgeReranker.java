package com.example.requirementrag.rerank;

import com.example.requirementrag.model.ChunkRecord;

import java.util.List;

/**
 * BGE 重排器接口，按查询相关性对候选分块重新排序。
 */
public interface BgeReranker {
    /**
     * 对候选分块按与查询的相关性重排。
     *
     * @param query      检索查询文本
     * @param candidates 待重排的候选分块（保持原始顺序）
     * @param topK       返回的相关性最高的分块数量
     * @return 按相关性降序排列的前 topK 个分块；候选为空时返回空列表
     */
    List<ChunkRecord> rerank(String query, List<ChunkRecord> candidates, int topK);
}
