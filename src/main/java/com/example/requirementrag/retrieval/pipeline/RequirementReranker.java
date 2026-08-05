package com.example.requirementrag.retrieval.pipeline;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.RagOutcome;

import java.util.List;

/** 与检索 profile 无关的需求证据重排边界。 */
public interface RequirementReranker {
    /**
     * 对检索到的需求证据候选进行重排并截取 limit 条。
     *
     * @param query      检索查询文本
     * @param documentId 文档 ID（供可观测性上报）
     * @param version    文档版本号（供可观测性上报）
     * @param candidates 待重排的候选分块
     * @param limit      返回结果的最大条数
     * @return 重排结果及状态/警告/诊断信息
     */
    RagOutcome<List<ChunkRecord>> rerank(String query, String documentId, String version,
                                         List<ChunkRecord> candidates, int limit);

    /** 空实现：不重排，仅按原序截取 limit 条并返回。 */
    static RequirementReranker passthrough() {
        return (query, documentId, version, candidates, limit) -> {
            List<ChunkRecord> values = candidates == null ? List.of()
                    : candidates.stream().limit(limit).toList();
            return RagOutcome.of(values.isEmpty()
                            ? com.example.requirementrag.model.RagOutcomeStatus.NO_RESULTS
                            : com.example.requirementrag.model.RagOutcomeStatus.SUCCESS,
                    values, "retrieval.rerank", 0, values.size());
        };
    }
}
