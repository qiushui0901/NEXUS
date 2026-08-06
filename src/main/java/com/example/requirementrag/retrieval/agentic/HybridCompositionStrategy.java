package com.example.requirementrag.retrieval.agentic;

import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalPipeline;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import org.springframework.stereotype.Component;

/**
 * 组合策略：委托现有 {@link RetrievalPipeline}，保持"需求 + 代码全查"的既有行为。
 * 它是编排器的主策略（第 0 跳默认策略）；后续新增策略（代码图、diff 直读等）
 * 只需实现 {@link RetrievalStrategy} 并注册为组件。
 */
@Component
public class HybridCompositionStrategy implements RetrievalStrategy {

    private final RetrievalPipeline pipeline;

    public HybridCompositionStrategy(RetrievalPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public StrategyResult execute(RetrievalRequest request) {
        RagOutcome<RetrievalBundle> outcome = pipeline.execute(request);
        return new StrategyResult("hybrid", outcome.data(), outcome.status(),
                outcome.warnings(), outcome.stageDiagnostics());
    }
}
