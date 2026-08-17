package com.example.requirementrag.evolution.policy;

import java.util.Map;
import java.util.Set;

/** 策略参数 allowlist 校验器。首期只允许受控参数进化。 */
public final class PolicyParameterValidator {

    private static final Set<String> SELECTOR_RULES = Set.of(
            "selector.requirement-review-strategy",
            "selector.code-intent-strategy"
    );

    /**
     * 当前真正进入检索链路的参数白名单。
     * <p>
     * 未接入执行的参数（weights.*、retrieval.topK.*、rerank.bge-enabled 等）不允许注册，
     * 避免“候选策略看起来不同、实际执行相同”的无效实验。
     * </p>
     */
    private static final Set<String> RANKING_WEIGHTS = Set.of();

    private static final Set<String> THRESHOLDS = Set.of(
            "orchestrator.max-hops",
            "reflector.min-requirement-hits"
    );

    private static final Set<String> FEATURE_FLAGS = Set.of();

    private PolicyParameterValidator() {
    }

    /** 校验策略参数；非法键或非法值抛出 IllegalArgumentException。 */
    public static void validate(RetrievalPolicy policy) {
        if (policy == null || policy.policyId() == null || policy.policyId().isBlank()
                || policy.version() == null || policy.version().isBlank()) {
            throw new IllegalArgumentException("policyId and version are required");
        }
        validateKeys("selectorRules", policy.selectorRules(), SELECTOR_RULES);
        validateKeys("rankingWeights", policy.rankingWeights(), RANKING_WEIGHTS);
        validateKeys("thresholds", policy.thresholds(), THRESHOLDS);
        validateKeys("featureFlags", policy.featureFlags(), FEATURE_FLAGS);

        Integer maxHops = policy.thresholds().get("orchestrator.max-hops");
        if (maxHops != null && (maxHops < 1 || maxHops > 5)) {
            throw new IllegalArgumentException("orchestrator.max-hops must be between 1 and 5");
        }
        Integer minRequirementHits = policy.thresholds().get("reflector.min-requirement-hits");
        if (minRequirementHits != null && minRequirementHits < 0) {
            throw new IllegalArgumentException("reflector.min-requirement-hits must be >= 0");
        }
        for (Map.Entry<String, Double> entry : policy.rankingWeights().entrySet()) {
            double value = entry.getValue();
            if (Double.isNaN(value) || value < 0 || value > 100) {
                throw new IllegalArgumentException(entry.getKey() + " must be between 0 and 100");
            }
        }
    }

    private static void validateKeys(String field, Map<?, ?> values, Set<String> allowed) {
        for (Object key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("Unsupported " + field + " key: " + key);
            }
        }
    }
}
