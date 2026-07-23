package com.example.requirementrag.model;

/** 存疑类型分类，用于标注问题所属领域。 */
public enum DoubtType {
    /** 配置项与参数规则。 */
    CONFIGURATION,
    /** 生命周期与状态流转。 */
    LIFECYCLE,
    /** 权限与访问控制。 */
    PERMISSION,
    /** 并发与竞态场景。 */
    CONCURRENCY,
    /** 数据重置与清理规则。 */
    DATA_RESET,
    /** 奖励与消耗数值。 */
    REWARD_COST,
    /** 交互与操作流程。 */
    INTERACTION,
    /** 规则冲突或语义歧义。 */
    CONFLICT_AMBIGUITY
}
