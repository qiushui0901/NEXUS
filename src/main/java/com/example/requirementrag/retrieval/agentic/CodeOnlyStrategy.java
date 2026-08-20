package com.example.requirementrag.retrieval.agentic;

import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.project.BusinessProjectCatalogService;
import com.example.requirementrag.project.BusinessProjectCodeSearchService;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 代码单线策略：只执行代码检索（不经需求管线），适用于代码实现类查询。
 * 命中为空时返回 NO_RESULTS 状态，供反射器触发补检。
 * 复用业务项目多仓库扇出，尊重 request.repositoryIds() 与显式公共库引用。
 */
@Component
public class CodeOnlyStrategy implements RetrievalStrategy {

    private final BusinessProjectCatalogService catalog;
    private final BusinessProjectCodeSearchService businessCodeSearch;

    public CodeOnlyStrategy(BusinessProjectCatalogService catalog,
                            BusinessProjectCodeSearchService businessCodeSearch) {
        this.catalog = catalog;
        this.businessCodeSearch = businessCodeSearch;
    }

    @Override
    public StrategyResult execute(RetrievalRequest request) {
        String resolvedProjectId = catalog.resolveProjectId(request.projectId());
        int limit = Math.min(Math.max(request.limit() == null ? 8 : request.limit(), 1), 50);
        try {
            List<CodeChunk> chunks = businessCodeSearch.search(request.query(), resolvedProjectId,
                    request.repositoryIds(), limit);
            List<String> allowedIds = catalog.repositoryScope(resolvedProjectId, request.repositoryIds())
                    .stream().map(r -> r.id()).toList();
            RetrievalBundle bundle = new RetrievalBundle(request.query(), request.profile(), resolvedProjectId,
                    null, null, List.of(), List.of(), chunks, allowedIds.isEmpty() ? List.of(resolvedProjectId) : allowedIds);
            RagOutcomeStatus status = chunks.isEmpty() ? RagOutcomeStatus.NO_RESULTS : RagOutcomeStatus.SUCCESS;
            return new StrategyResult("code", bundle, status, List.of(), List.of());
        } catch (RuntimeException exception) {
            RetrievalBundle bundle = new RetrievalBundle(request.query(), request.profile(), resolvedProjectId,
                    null, null, List.of(), List.of(), List.of(), List.of(resolvedProjectId));
            return new StrategyResult("code", bundle, RagOutcomeStatus.DEGRADED,
                    List.of(new RagWarning("code", "CODE_RETRIEVAL_UNAVAILABLE",
                            "代码检索暂时不可用: " + exception.getMessage(), 0)), List.of());
        }
    }
}
