package com.example.requirementrag.retrieval.agentic;

import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalPipeline;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import org.springframework.stereotype.Component;

/**
 * 需求单线策略：强制以 REQUIREMENT_REVIEW 画像执行检索，跳过代码分支，
 * 仅返回需求证据。适用于需求评审类查询，省掉代码检索的延迟与成本。
 */
@Component
public class RequirementsOnlyStrategy implements RetrievalStrategy {

    private final RetrievalPipeline pipeline;

    public RequirementsOnlyStrategy(RetrievalPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public StrategyResult execute(RetrievalRequest request) {
        RetrievalRequest reviewOnly = new RetrievalRequest(request.query(),
                RetrievalProfile.REQUIREMENT_REVIEW, request.projectId(),
                request.documentId(), request.version(), request.limit());
        RagOutcome<RetrievalBundle> outcome = pipeline.execute(reviewOnly);
        return new StrategyResult("requirements", outcome.data(), outcome.status(),
                outcome.warnings(), outcome.stageDiagnostics());
    }
}
