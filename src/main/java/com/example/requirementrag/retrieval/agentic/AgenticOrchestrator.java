package com.example.requirementrag.retrieval.agentic;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.RagStageDiagnostic;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.retrieval.agentic.EvidenceReflector.ReflectionVerdict;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 认知层编排器：规划-检索-反思-迭代的 Agentic 循环宿主。
 * <p>
 * 循环语义：按 {@link #maxHops} 逐跳执行策略链（第 0 跳为主策略，通常为组合全查），
 * 每跳用 {@link EvidenceReflector} 自评：CONFIDENT 立即交付；INSUFFICIENT 用后续策略
 * 补检并与已有证据合并；NOT_RETRIEVABLE 或耗尽跳数时降级交付（保留已得证据 + DEGRADED 警告）。
 * </p>
 */
@Component
public class AgenticOrchestrator {

    /** 默认最大跳数：第 0 跳组合全查 + 第 1 跳补检。 */
    public static final int DEFAULT_MAX_HOPS = 2;

    private static final String STAGE = "agentic.orchestrate";

    private final List<RetrievalStrategy> strategies;
    private final EvidenceReflector reflector;
    private final RetrievalStrategySelector selector;
    private final int maxHops;

    @Autowired
    public AgenticOrchestrator(List<RetrievalStrategy> strategies, EvidenceReflector reflector) {
        this(strategies, reflector, new RetrievalStrategySelector.RuleBasedRetrievalStrategySelector(),
                DEFAULT_MAX_HOPS);
    }

    public AgenticOrchestrator(List<RetrievalStrategy> strategies, EvidenceReflector reflector, int maxHops) {
        this(strategies, reflector, new RetrievalStrategySelector.RuleBasedRetrievalStrategySelector(), maxHops);
    }

    public AgenticOrchestrator(List<RetrievalStrategy> strategies, EvidenceReflector reflector,
                               RetrievalStrategySelector selector, int maxHops) {
        if (strategies == null || strategies.isEmpty()) {
            throw new IllegalArgumentException("at least one retrieval strategy required");
        }
        this.strategies = List.copyOf(strategies);
        this.reflector = reflector;
        this.selector = selector;
        this.maxHops = Math.max(1, maxHops);
    }

    /**
     * 执行一次编排检索。
     *
     * @param request 检索请求
     * @return 编排结果：证据充分时按最后状态交付，不足/失败时降级交付并附编排警告
     */
    public RagOutcome<RetrievalBundle> execute(RetrievalRequest request) {
        StrategyResult merged = null;
        List<RagWarning> orchestrationWarnings = new ArrayList<>();
        for (int hop = 0; hop < maxHops; hop++) {
            RetrievalStrategy defaultStrategy = strategies.get(Math.min(hop, strategies.size() - 1));
            RetrievalStrategy strategy = hop == 0
                    ? selector.select(strategies, request).orElseGet(() -> strategies.get(0))
                    : RetrievalStrategySelector.byName(strategies, "hybrid").orElseGet(() -> defaultStrategy);
            StrategyResult current = strategy.execute(request);
            if (merged != null) {
                current = merge(merged, current);
            }
            EvidenceReflector.ReflectionResult reflection = reflector.evaluate(current);
            ReflectionVerdict verdict = reflection.verdict();
            if (verdict == ReflectionVerdict.CONFIDENT) {
                return deliver(current, orchestrationWarnings, hop + 1, false);
            }
            if (verdict == ReflectionVerdict.NOT_RETRIEVABLE) {
                orchestrationWarnings.add(new RagWarning(STAGE, "ORCHESTRATION_NOT_RETRIEVABLE",
                        "检索核心阶段失败，降级交付已有结果", hop + 1));
                return deliver(current, orchestrationWarnings, hop + 1, true);
            }
            orchestrationWarnings.add(insufficientWarning(hop + 1,
                    "需求证据命中不足，已触发补检（第 " + (hop + 2) + " 跳）"));
            merged = current;
        }
        return deliver(merged, orchestrationWarnings, maxHops, true);
    }

    /** 合并两跳证据：需求/正文/代码分别按值去重（record equals），保留先到者顺序。 */
    private StrategyResult merge(StrategyResult base, StrategyResult supplement) {
        RetrievalBundle baseBundle = base.bundle();
        RetrievalBundle supplementBundle = supplement.bundle();
        List<ChunkRecord> requirements = deduplicate(baseBundle.requirementEvidence(),
                supplementBundle.requirementEvidence());
        List<ChunkRecord> corpus = deduplicate(baseBundle.requirementCorpus(),
                supplementBundle.requirementCorpus());
        List<CodeChunk> code = deduplicate(baseBundle.codeEvidence(), supplementBundle.codeEvidence());
        RetrievalBundle mergedBundle = new RetrievalBundle(baseBundle.query(), baseBundle.profile(),
                baseBundle.resolvedProjectId(), baseBundle.documentId(), baseBundle.version(),
                requirements, corpus, code);
        List<RagWarning> warnings = new ArrayList<>(base.warnings());
        for (RagWarning warning : supplement.warnings()) {
            if (!warnings.contains(warning)) {
                warnings.add(warning);
            }
        }
        List<RagStageDiagnostic> diagnostics = new ArrayList<>(base.diagnostics());
        for (RagStageDiagnostic diagnostic : supplement.diagnostics()) {
            if (!diagnostics.contains(diagnostic)) {
                diagnostics.add(diagnostic);
            }
        }
        return new StrategyResult("hybrid", mergedBundle, base.status(), warnings, diagnostics);
    }

    private <T> List<T> deduplicate(List<T> first, List<T> second) {
        Map<T, T> seen = new LinkedHashMap<>();
        for (T item : first) {
            seen.put(item, item);
        }
        for (T item : second) {
            seen.putIfAbsent(item, item);
        }
        return List.copyOf(seen.values());
    }

    private RagOutcome<RetrievalBundle> deliver(StrategyResult result, List<RagWarning> warnings,
                                                int hops, boolean degraded) {
        RagOutcomeStatus status = degraded || result == null || result.status() == RagOutcomeStatus.FAILED
                ? RagOutcomeStatus.DEGRADED : result.status();
        List<RagWarning> combined = new ArrayList<>();
        if (result != null) {
            combined.addAll(result.warnings());
        }
        combined.addAll(warnings);
        List<RagStageDiagnostic> diagnostics = new ArrayList<>();
        if (result != null) {
            diagnostics.addAll(result.diagnostics());
        }
        diagnostics.add(new RagStageDiagnostic(STAGE, status, 0, hops));
        RetrievalBundle bundle = result == null ? null : result.bundle();
        return new RagOutcome<>(status, bundle, combined, diagnostics);
    }

    private RagWarning insufficientWarning(int hop, String message) {
        return new RagWarning(STAGE, "ORCHESTRATION_INSUFFICIENT_EVIDENCE", message, hop);
    }
}
