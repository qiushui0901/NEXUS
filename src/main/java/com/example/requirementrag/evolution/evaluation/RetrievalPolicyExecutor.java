package com.example.requirementrag.evolution.evaluation;

import com.example.requirementrag.evolution.policy.RetrievalPolicy;

import java.util.List;

/** 策略实验执行器：在固定数据集样本上运行指定策略并返回排序 ID。 */
public interface RetrievalPolicyExecutor {

    /** 对单个评测样本执行检索，返回预测的相关 ID 排序。 */
    List<String> execute(EvaluationCase evalCase, RetrievalPolicy policy);
}
