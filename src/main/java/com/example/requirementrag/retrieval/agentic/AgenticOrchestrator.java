package com.example.requirementrag.retrieval.agentic;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.evolution.experience.EvolutionTrace;
import com.example.requirementrag.evolution.experience.RetrievalExperienceRecorder;
import com.example.requirementrag.evolution.policy.PolicyDrivenRetrievalStrategySelector;
import com.example.requirementrag.evolution.policy.RetrievalPolicy;
import com.example.requirementrag.evolution.policy.RetrievalPolicyRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.RagStageDiagnostic;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.retrieval.agentic.EvidenceReflector.ReflectionVerdict;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import org.springframework.beans.factory.ObjectProvider;
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

    private static final String BASELINE_POLICY_VERSION = "baseline-v1";

    private final List<RetrievalStrategy> strategies;
    private final EvidenceReflector reflector;
    private final RetrievalStrategySelector selector;
    private final int maxHops;
    private final RetrievalExperienceRecorder experienceRecorder;
    private final RagProperties properties;
    private final RetrievalPolicyRegistry policyRegistry;

    @Autowired
    public AgenticOrchestrator(List<RetrievalStrategy> strategies, EvidenceReflector reflector,
                               RetrievalStrategySelector selector,
                               ObjectProvider<RetrievalExperienceRecorder> experienceRecorderProvider,
                               ObjectProvider<RagProperties> propertiesProvider,
                               ObjectProvider<RetrievalPolicyRegistry> policyRegistryProvider) {
        this(strategies, reflector, selector, DEFAULT_MAX_HOPS,
                experienceRecorderProvider.getIfAvailable(), propertiesProvider.getIfAvailable(),
                policyRegistryProvider.getIfAvailable());
    }

    public AgenticOrchestrator(List<RetrievalStrategy> strategies, EvidenceReflector reflector) {
        this(strategies, reflector, new RetrievalStrategySelector.RuleBasedRetrievalStrategySelector(),
                DEFAULT_MAX_HOPS, null, null, null);
    }

    public AgenticOrchestrator(List<RetrievalStrategy> strategies, EvidenceReflector reflector, int maxHops) {
        this(strategies, reflector, new RetrievalStrategySelector.RuleBasedRetrievalStrategySelector(),
                maxHops, null, null, null);
    }

    public AgenticOrchestrator(List<RetrievalStrategy> strategies, EvidenceReflector reflector,
                               RetrievalStrategySelector selector, int maxHops) {
        this(strategies, reflector, selector, maxHops, null, null, null);
    }

    public AgenticOrchestrator(List<RetrievalStrategy> strategies, EvidenceReflector reflector,
                               RetrievalStrategySelector selector, int maxHops,
                               RetrievalExperienceRecorder experienceRecorder, RagProperties properties) {
        this(strategies, reflector, selector, maxHops, experienceRecorder, properties, null);
    }

    public AgenticOrchestrator(List<RetrievalStrategy> strategies, EvidenceReflector reflector,
                               RetrievalStrategySelector selector, int maxHops,
                               RetrievalExperienceRecorder experienceRecorder, RagProperties properties,
                               RetrievalPolicyRegistry policyRegistry) {
        if (strategies == null || strategies.isEmpty()) {
            throw new IllegalArgumentException("at least one retrieval strategy required");
        }
        this.strategies = List.copyOf(strategies);
        this.reflector = reflector;
        this.selector = selector;
        this.maxHops = Math.max(1, maxHops);
        this.experienceRecorder = experienceRecorder;
        this.properties = properties;
        this.policyRegistry = policyRegistry;
    }

    /**
     * 执行一次编排检索，使用当前激活策略（若 evolution 启用且存在 active policy）。
     *
     * @param request 检索请求
     * @return 编排结果：证据充分时按最后状态交付，不足/失败时降级交付并附编排警告
     */
    public RagOutcome<RetrievalBundle> execute(RetrievalRequest request) {
        return execute(request, null);
    }

    /**
     * 使用显式策略执行一次编排检索；用于离线实验隔离基线与候选策略。
     * 传入 {@code policyOverride} 时忽略 active.json，完全按该策略的参数执行。
     *
     * @param request        检索请求
     * @param policyOverride 实验指定的策略；为 null 时走生产路径（受 evolution.enabled 控制）
     * @return 编排结果
     */
    public RagOutcome<RetrievalBundle> execute(RetrievalRequest request, RetrievalPolicy policyOverride) {
        long startedNanos = System.nanoTime();
        EvolutionTrace trace = experienceRecorder == null ? null : EvolutionTrace.start(request,
                activePolicyVersion(policyOverride), configHash(), indexVersion(), null);
        StrategyResult merged = null;
        List<RagWarning> orchestrationWarnings = new ArrayList<>();
        List<String> degradedStages = new ArrayList<>();
        Integer configuredMaxHops = policyMaxHops(policyOverride);
        int effectiveMaxHops = configuredMaxHops == null ? maxHops : configuredMaxHops;
        for (int hop = 0; hop < effectiveMaxHops; hop++) {
            long hopStartedNanos = System.nanoTime();
            RetrievalStrategy defaultStrategy = strategies.get(Math.min(hop, strategies.size() - 1));
            RetrievalStrategy strategy = hop == 0
                    ? selectInitialStrategy(request, policyOverride)
                    : RetrievalStrategySelector.byName(strategies, "hybrid").orElseGet(() -> defaultStrategy);
            StrategyResult current = strategy.execute(request);
            if (merged != null) {
                current = merge(merged, current);
            }
            EvidenceReflector.ReflectionResult reflection = reflector.evaluate(current,
                    policyMinRequirementHits(policyOverride));
            if (trace != null) {
                trace.recordHop(hop, strategy.name(), current == null ? null : current.bundle(),
                        reflection, System.nanoTime() - hopStartedNanos);
            }
            if (current != null && current.status() == RagOutcomeStatus.DEGRADED) {
                degradedStages.add(strategy.name());
            }
            ReflectionVerdict verdict = reflection.verdict();
            if (verdict == ReflectionVerdict.CONFIDENT) {
                RagOutcome<RetrievalBundle> outcome = deliver(current, orchestrationWarnings, hop + 1, false);
                record(trace, outcome, hop + 1, orchestrationWarnings, current == null ? List.of()
                        : current.diagnostics(), degradedStages, startedNanos);
                return outcome;
            }
            if (verdict == ReflectionVerdict.NOT_RETRIEVABLE) {
                orchestrationWarnings.add(new RagWarning(STAGE, "ORCHESTRATION_NOT_RETRIEVABLE",
                        "检索核心阶段失败，降级交付已有结果", hop + 1));
                RagOutcome<RetrievalBundle> outcome = deliver(current, orchestrationWarnings, hop + 1, true);
                record(trace, outcome, hop + 1, orchestrationWarnings, current == null ? List.of()
                        : current.diagnostics(), degradedStages, startedNanos);
                return outcome;
            }
            orchestrationWarnings.add(insufficientWarning(hop + 1,
                    "需求证据命中不足，已触发补检（第 " + (hop + 2) + " 跳）"));
            merged = current;
        }
        RagOutcome<RetrievalBundle> finalOutcome = deliver(merged, orchestrationWarnings, effectiveMaxHops, true);
        record(trace, finalOutcome, effectiveMaxHops, orchestrationWarnings, merged == null ? List.of()
                : merged.diagnostics(), degradedStages, startedNanos);
        return finalOutcome;
    }

    private RetrievalStrategy selectInitialStrategy(RetrievalRequest request, RetrievalPolicy policyOverride) {
        if (policyOverride != null) {
            return PolicyDrivenRetrievalStrategySelector.forPolicy(policyOverride)
                    .select(strategies, request).orElseGet(() -> strategies.get(0));
        }
        return selector.select(strategies, request).orElseGet(() -> strategies.get(0));
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

    private void record(EvolutionTrace trace, RagOutcome<RetrievalBundle> outcome, int hops,
                        List<RagWarning> warnings, List<RagStageDiagnostic> diagnostics,
                        List<String> degradedStages, long startedNanos) {
        if (trace == null || experienceRecorder == null) {
            return;
        }
        experienceRecorder.recordAsync(trace.finish(outcome, hops, elapsedMillis(startedNanos),
                warnings, diagnostics, degradedStages));
    }

    private String configHash() {
        return properties == null || properties.retrieval() == null ? "unknown"
                : properties.retrieval().fingerprint();
    }

    private Integer policyMinRequirementHits(RetrievalPolicy policyOverride) {
        RetrievalPolicy policy = activePolicy(policyOverride);
        if (policy == null || policy.thresholds() == null) {
            return null;
        }
        return policy.thresholds().get("reflector.min-requirement-hits");
    }

    private Integer policyMaxHops(RetrievalPolicy policyOverride) {
        RetrievalPolicy policy = activePolicy(policyOverride);
        if (policy == null || policy.thresholds() == null) {
            return null;
        }
        return policy.thresholds().get("orchestrator.max-hops");
    }

    private String activePolicyVersion(RetrievalPolicy policyOverride) {
        RetrievalPolicy policy = activePolicy(policyOverride);
        return policy == null ? BASELINE_POLICY_VERSION : policy.version();
    }

    private RetrievalPolicy activePolicy(RetrievalPolicy policyOverride) {
        if (policyOverride != null) {
            return policyOverride;
        }
        if (properties == null || !properties.evolution().enabled()) {
            return null;
        }
        return policyRegistry == null ? null : policyRegistry.active();
    }

    private String indexVersion() {
        return "unknown";
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
