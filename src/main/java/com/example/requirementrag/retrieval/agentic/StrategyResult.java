package com.example.requirementrag.retrieval.agentic;

import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.RagStageDiagnostic;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;

import java.util.List;

/**
 * 策略执行结果：携带证据包、状态与诊断，并暴露命中统计供反射器自评。
 * 各列表在构造时做防御性拷贝，保证不可变。
 */
public record StrategyResult(
        String strategy,
        RetrievalBundle bundle,
        RagOutcomeStatus status,
        List<RagWarning> warnings,
        List<RagStageDiagnostic> diagnostics
) {
    /** 紧凑构造器：归一化可能为 null 的警告与诊断列表。 */
    public StrategyResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /** 需求证据命中条数（反射器自评的主要信号之一）。 */
    public int requirementHitCount() {
        return bundle == null ? 0 : bundle.requirementEvidence().size();
    }

    /** 代码证据命中条数。 */
    public int codeHitCount() {
        return bundle == null ? 0 : bundle.codeEvidence().size();
    }
}
