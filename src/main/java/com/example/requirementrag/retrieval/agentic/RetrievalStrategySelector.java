package com.example.requirementrag.retrieval.agentic;

import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 策略选择器：按查询类型从策略池中选出第 0 跳策略。
 * 选择结果只影响首跳；证据不足时编排器仍会用组合策略补检，保证信息完整性。
 */
public interface RetrievalStrategySelector {

    /**
     * 从策略池中选择首跳策略。
     *
     * @param strategies 可用策略池
     * @param request    检索请求（画像 + 查询文本）
     * @return 选中的策略；无匹配时返回空（由编排器回退到默认策略）
     */
    Optional<RetrievalStrategy> select(List<RetrievalStrategy> strategies, RetrievalRequest request);

    /** 按策略名从池中查找。 */
    static Optional<RetrievalStrategy> byName(List<RetrievalStrategy> strategies, String name) {
        return strategies.stream().filter(strategy -> name.equals(strategy.name())).findFirst();
    }

    /**
     * 规则版选择器：需求评审画像 → 需求单线；代码意图查询 → 代码单线；
     * 其余返回空（编排器走默认组合策略）。
     */
    class RuleBasedRetrievalStrategySelector implements RetrievalStrategySelector {

        private static final String[] CODE_INTENT_TERMS = {
                "实现", "方法", "接口", "代码", "如何", "调用", "类", "函数", "服务",
                "impl", "service", "controller", "how", "implement", "method"
        };

        @Override
        public Optional<RetrievalStrategy> select(List<RetrievalStrategy> strategies, RetrievalRequest request) {
            if (request.profile() == RetrievalProfile.REQUIREMENT_REVIEW) {
                Optional<RetrievalStrategy> requirements = byName(strategies, "requirements");
                if (requirements.isPresent()) {
                    return requirements;
                }
            }
            if (hasCodeIntent(request.query())) {
                Optional<RetrievalStrategy> code = byName(strategies, "code");
                if (code.isPresent()) {
                    return code;
                }
            }
            return Optional.empty();
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
}
