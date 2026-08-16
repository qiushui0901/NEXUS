package com.example.requirementrag.evolution.policy;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.retrieval.agentic.RetrievalStrategy;
import com.example.requirementrag.retrieval.agentic.RetrievalStrategySelector;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 从版本化策略读取规则的首跳策略选择器。
 * <p>
 * 当 active policy 缺失或规则解析失败时回退到内置规则选择器，保证检索不中断。
 * evolution 未启用时忽略 active.json，完全走内置规则选择器。
 * </p>
 */
@Component
public class PolicyDrivenRetrievalStrategySelector implements RetrievalStrategySelector {

    private static final String[] CODE_INTENT_TERMS = {
            "实现", "方法", "接口", "代码", "如何", "调用", "类", "函数", "服务",
            "impl", "service", "controller", "how", "implement", "method"
    };

    private final RetrievalPolicyRegistry policyRegistry;
    private final RetrievalPolicy fixedPolicy;
    private final boolean evolutionEnabled;
    private final RetrievalStrategySelector fallback = new RuleBasedRetrievalStrategySelector();

    @Autowired
    public PolicyDrivenRetrievalStrategySelector(RetrievalPolicyRegistry policyRegistry,
                                                 RagProperties properties) {
        this.policyRegistry = policyRegistry;
        this.fixedPolicy = null;
        this.evolutionEnabled = properties != null && properties.evolution().enabled();
    }

    /** 兼容构造器：未显式传入 properties 时视为 evolution 已启用（供非 Spring 测试使用）。 */
    public PolicyDrivenRetrievalStrategySelector(RetrievalPolicyRegistry policyRegistry) {
        this(policyRegistry, null);
    }

    private PolicyDrivenRetrievalStrategySelector(RetrievalPolicy fixedPolicy) {
        this.policyRegistry = null;
        this.fixedPolicy = fixedPolicy;
        this.evolutionEnabled = true;
    }

    /** 为离线实验创建固定策略选择器，完全忽略 active.json。 */
    public static PolicyDrivenRetrievalStrategySelector forPolicy(RetrievalPolicy policy) {
        return new PolicyDrivenRetrievalStrategySelector(policy);
    }

    @Override
    public Optional<RetrievalStrategy> select(List<RetrievalStrategy> strategies, RetrievalRequest request) {
        RetrievalPolicy policy = fixedPolicy != null ? fixedPolicy : resolveActivePolicy();
        if (policy == null || policy.selectorRules().isEmpty()) {
            return fallback.select(strategies, request);
        }
        String strategyName = null;
        if (request.profile() == RetrievalProfile.REQUIREMENT_REVIEW) {
            strategyName = policy.selectorRules().get("selector.requirement-review-strategy");
        } else if (hasCodeIntent(request.query())) {
            strategyName = policy.selectorRules().get("selector.code-intent-strategy");
        }
        if (strategyName != null && !strategyName.isBlank()) {
            Optional<RetrievalStrategy> byName = RetrievalStrategySelector.byName(strategies, strategyName);
            if (byName.isPresent()) {
                return byName;
            }
        }
        return fallback.select(strategies, request);
    }

    private RetrievalPolicy resolveActivePolicy() {
        if (!evolutionEnabled || policyRegistry == null) {
            return null;
        }
        return policyRegistry.active();
    }

    private boolean hasCodeIntent(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        for (String term : CODE_INTENT_TERMS) {
            if (normalized.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
