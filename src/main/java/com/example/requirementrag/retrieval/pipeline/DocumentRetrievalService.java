package com.example.requirementrag.retrieval.pipeline;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.RagOutcome;

import java.util.List;

/**
 * 文档检索服务：封装需求文档的混合检索、正文滚动与重排。
 * 与 {@link CodeRetrievalService} 分离，保持文档/代码双体系独立演进，
 * 编排层 {@link RetrievalPipeline} 仍统一负责路由、超时、熔断与降级语义。
 */
public interface DocumentRetrievalService {

    /**
     * 执行文档混合检索（dense+sparse RRF）。
     *
     * @param collection Qdrant collection 名称
     * @param query      查询文本
     * @param documentId 文档 ID
     * @param version    文档版本
     * @return 检索到的分块列表
     */
    List<ChunkRecord> search(String collection, String query, String documentId, String version);

    /**
     * 滚动读取指定文档版本的全部正文 payload。
     *
     * @param collection Qdrant collection 名称
     * @param documentId 文档 ID
     * @param version    文档版本
     * @return 正文明细
     */
    List<ChunkRecord> scrollCorpus(String collection, String documentId, String version);

    /**
     * 对候选分块执行重排（BGE→可选 LLM）。
     *
     * @param query      查询文本
     * @param documentId 文档 ID
     * @param version    版本
     * @param candidates 待重排候选
     * @param limit      截断上限
     * @return 重排结果，携带状态/警告/诊断
     */
    RagOutcome<List<ChunkRecord>> rerank(String query, String documentId, String version,
                                         List<ChunkRecord> candidates, int limit);
}
