package com.example.requirementrag.rerank;

import com.example.requirementrag.model.ChunkRecord;

import java.util.List;

/**
 * BGE 重排器接口，按查询相关性对候选分块重新排序。
 */
public interface BgeReranker {
    /** 对候选分块重排并返回 topK 结果。 */
    List<ChunkRecord> rerank(String query, List<ChunkRecord> candidates, int topK);
}
