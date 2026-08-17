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
 * 显式把 policy 传给编排器，保证基线与候选策略在离线实验中真正隔离。
 * </p>
 */
@Component
public class AgenticRetrievalPolicyExecutor implements RetrievalPolicyExecutor {

    private final AgenticOrchestrator orchestrator;

    public AgenticRetrievalPolicyExecutor(AgenticOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public ExecutionResult execute(EvaluationCase evalCase, RetrievalPolicy policy,
                                   long randomSeed, int repetition) {
        // seed 与 repetition 都进入执行上下文：不同 seed/repetition 使用不同缓存键，
        // 并为后续可能的随机检索/重排策略提供可复现的随机源。
        long effectiveSeed = randomSeed * 31L + repetition;
        RetrievalRequest request = new RetrievalRequest(evalCase.query(), RetrievalProfile.DEVELOPMENT_PLAN,
                evalCase.projectId(), null, evalCase.version(), 10, effectiveSeed);
        RagOutcome<RetrievalBundle> outcome = orchestrator.execute(request, policy);
        if (outcome == null || outcome.status() == null) {
            return new ExecutionResult(List.of(), "FAILED");
        }
        List<String> ids = new ArrayList<>();
        if (outcome.data() != null) {
            RetrievalBundle bundle = outcome.data();
            for (ChunkRecord chunk : bundle.requirementEvidence()) {
                ids.add(chunk.id());
            }
            for (CodeChunk chunk : bundle.codeEvidence()) {
                ids.add(chunk.id());
            }
        }
        return new ExecutionResult(List.copyOf(ids), outcome.status().name());
    }
}
