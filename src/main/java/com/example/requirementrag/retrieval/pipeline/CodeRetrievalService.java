package com.example.requirementrag.retrieval.pipeline;

import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;

import java.util.List;

/**
 * 代码检索服务：封装代码混合检索与多仓库扇出。
 * 与 {@link DocumentRetrievalService} 分离，便于后续引入 code-aware 分词、
 * 独立 embedding 与结构化重排。
 */
public interface CodeRetrievalService {

    /**
     * 按业务项目与可选仓库范围执行代码检索。
     *
     * @param query              查询文本
     * @param businessProjectId  业务项目 ID
     * @param repositoryIds      请求限定的仓库 ID 列表（可为空表示默认范围）
     * @param limit              返回上限
     * @return 代码块列表
     */
    List<CodeChunk> search(String query, String businessProjectId, List<String> repositoryIds, int limit);

    /**
     * 直接按业务项目检索（不经仓库扇出，供兼容路径使用）。
     *
     * @param query             查询文本
     * @param businessProjectId 业务项目 ID
     * @param limit             返回上限
     * @return 代码块列表
     */
    List<CodeChunk> search(String query, String businessProjectId, int limit);

    /** 带仓库级降级状态的代码检索；旧实现默认包装原有 List 契约。 */
    default RagOutcome<List<CodeChunk>> searchOutcome(String query, String businessProjectId,
                                                       List<String> repositoryIds, int limit) {
        long started = System.nanoTime();
        try {
            List<CodeChunk> data = search(query, businessProjectId, repositoryIds, limit);
            List<CodeChunk> safe = data == null ? List.of() : List.copyOf(data);
            RagOutcomeStatus status = safe.isEmpty() ? RagOutcomeStatus.NO_RESULTS : RagOutcomeStatus.SUCCESS;
            return RagOutcome.of(status, safe, "code.hybrid_search",
                    (System.nanoTime() - started) / 1_000_000, safe.size());
        } catch (RuntimeException exception) {
            return RagOutcome.failed(List.of(), "code.hybrid_search",
                    "CODE_RETRIEVAL_UNAVAILABLE", "代码检索暂时不可用",
                    (System.nanoTime() - started) / 1_000_000);
        }
    }
}
