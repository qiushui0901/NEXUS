package com.example.requirementrag.retrieval.agentic;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 代码单线策略：只执行代码检索（不经需求管线），适用于代码实现类查询。
 * 命中为空时返回 NO_RESULTS 状态，供反射器触发补检。
 */
@Component
public class CodeOnlyStrategy implements RetrievalStrategy {

    private final CodeKnowledgeService codeKnowledgeService;
    private final ProjectRegistry projectRegistry;

    public CodeOnlyStrategy(CodeKnowledgeService codeKnowledgeService, ProjectRegistry projectRegistry) {
        this.codeKnowledgeService = codeKnowledgeService;
        this.projectRegistry = projectRegistry;
    }

    @Override
    public StrategyResult execute(RetrievalRequest request) {
        String projectId = request.projectId() == null || request.projectId().isBlank()
                ? projectRegistry.defaultProject().id() : request.projectId().trim();
        int limit = Math.min(Math.max(request.limit() == null ? 8 : request.limit(), 1), 50);
        try {
            List<CodeChunk> chunks = codeKnowledgeService.search(request.query(), projectId, limit);
            RetrievalBundle bundle = new RetrievalBundle(request.query(), request.profile(), projectId,
                    null, null, List.of(), chunks);
            RagOutcomeStatus status = chunks.isEmpty() ? RagOutcomeStatus.NO_RESULTS : RagOutcomeStatus.SUCCESS;
            return new StrategyResult("code", bundle, status, List.of(), List.of());
        } catch (RuntimeException exception) {
            RetrievalBundle bundle = new RetrievalBundle(request.query(), request.profile(), projectId,
                    null, null, List.of(), List.of());
            return new StrategyResult("code", bundle, RagOutcomeStatus.DEGRADED,
                    List.of(new RagWarning("code", "CODE_RETRIEVAL_UNAVAILABLE",
                            "代码检索暂时不可用: " + exception.getMessage(), 0)), List.of());
        }
    }
}
