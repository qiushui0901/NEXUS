package com.example.requirementrag.rerank;

import com.example.requirementrag.model.ChunkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 带降级能力的 BGE 重排器装饰器，服务不可用时回退为原始顺序。
 */
public class ResilientBgeReranker implements BgeReranker {

    private static final Logger log = LoggerFactory.getLogger(ResilientBgeReranker.class);

    private final BgeReranker delegate;

    /** 包装实际重排器实现。 */
    public ResilientBgeReranker(BgeReranker delegate) {
        this.delegate = delegate;
    }

    /** 委托重排，失败时记录警告并按混合检索原始顺序截取 topK。 */
    @Override
    public List<ChunkRecord> rerank(String query, List<ChunkRecord> candidates, int topK) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        try {
            return delegate.rerank(query, candidates, topK);
        }
        catch (RuntimeException exception) {
            log.atWarn().setCause(exception).addKeyValue("event", "bge_rerank_fallback")
                    .log("BGE reranker unavailable, using hybrid order");
            return candidates.stream().limit(topK).toList();
        }
    }

    /** 委托带分数重排；失败时返回空列表（调用方禁用依赖分数的规则）。 */
    @Override
    public List<com.example.requirementrag.model.ScoredChunk> rerankScored(
            String query, List<ChunkRecord> candidates, int topK) {
        try {
            return delegate.rerankScored(query, candidates, topK);
        }
        catch (RuntimeException exception) {
            log.atWarn().setCause(exception).addKeyValue("event", "bge_rerank_scored_fallback")
                    .log("BGE scored rerank unavailable, disabling score-gap rules");
            return List.of();
        }
    }
}
