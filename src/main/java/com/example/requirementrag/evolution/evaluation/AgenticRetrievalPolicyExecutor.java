package com.example.requirementrag.evolution.evaluation;

import com.example.requirementrag.evolution.policy.RetrievalPolicy;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.retrieval.agentic.AgenticOrchestrator;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 {@link AgenticOrchestrator} 的策略执行器。
 * <p>
 * 当前版本策略参数尚未完全注入编排器，先提供可运行的检索执行入口；
 * M4 的 PolicyDriven 选择器接入后，本执行器会把 policy 上下文传给编排器。
 * </p>
 */
@Component
public class AgenticRetrievalPolicyExecutor implements RetrievalPolicyExecutor {

    private final AgenticOrchestrator orchestrator;

    public AgenticRetrievalPolicyExecutor(AgenticOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public List<String> execute(EvaluationCase evalCase, RetrievalPolicy policy) {
        RetrievalRequest request = new RetrievalRequest(evalCase.query(), RetrievalProfile.DEVELOPMENT_PLAN,
                evalCase.projectId(), null, evalCase.version(), 10);
        RagOutcome<RetrievalBundle> outcome = orchestrator.execute(request);
        List<String> ids = new ArrayList<>();
        if (outcome != null && outcome.data() != null) {
            RetrievalBundle bundle = outcome.data();
            for (ChunkRecord chunk : bundle.requirementEvidence()) {
                ids.add(chunk.id());
            }
            for (CodeChunk chunk : bundle.codeEvidence()) {
                ids.add(chunk.id());
            }
        }
        return List.copyOf(ids);
    }
}
