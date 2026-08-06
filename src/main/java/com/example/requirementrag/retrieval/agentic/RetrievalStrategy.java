package com.example.requirementrag.retrieval.agentic;

import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;

/**
 * 检索策略契约：编排层的"动作"单元。
 * 每个策略封装一种（或一组）检索方式的完整执行，返回统一的 {@link StrategyResult}。
 * 策略只负责"执行"，是否足够由 {@link EvidenceReflector} 评判，是否继续由编排器决定。
 */
public interface RetrievalStrategy {

    /** 策略名（供选择器按名查找），默认取类简单名。 */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * 执行一次检索。
     *
     * @param request 检索请求（query、profile、projectId、version、limit）
     * @return 策略产出：证据包、状态与诊断
     */
    StrategyResult execute(RetrievalRequest request);
}
