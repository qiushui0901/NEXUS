package com.example.requirementrag.evolution.evaluation;

import com.example.requirementrag.evolution.policy.RetrievalPolicy;

import java.util.List;

/** 策略实验执行器：在固定数据集样本上运行指定策略并返回排序 ID 与执行状态。 */
public interface RetrievalPolicyExecutor {

    /**
     * 对单个评测样本执行一次检索。
     *
     * @param evalCase   评测样本
     * @param policy     要执行的策略（必须真正参与执行，不能忽略）
     * @param randomSeed 实验随机种子
     * @param repetition 当前重复序号（从 1 开始）
     * @return 预测 ID 排序与状态（SUCCESS / DEGRADED / FAILED）
     */
    ExecutionResult execute(EvaluationCase evalCase, RetrievalPolicy policy, long randomSeed, int repetition);

    /** 一次策略执行的返回值。 */
    record ExecutionResult(List<String> ids, String status) {
        public ExecutionResult {
            ids = ids == null ? List.of() : List.copyOf(ids);
            status = status == null || status.isBlank() ? "SUCCESS" : status;
        }
    }
}
