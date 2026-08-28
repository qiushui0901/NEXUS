package com.example.requirementrag.knowledge.multisource.entity;

/**
 * 实体检索的可选召回方式（图/向量增强召回，dev md §13 可选召回）。
 *
 * <ul>
 *   <li>{@link #DETERMINISTIC}：规则/结构化召回（解析 → 全版本聚合），现状默认；</li>
 *   <li>{@link #GRAPH_VECTOR}：实体识别 → 局部图一跳/二跳扩展 + 可选 Claim 向量补召回 → 实体/关系/证据组织；</li>
 *   <li>{@link #HYBRID}：两路并集（确定性 + 图/向量补召回都进召回集）。</li>
 * </ul>
 *
 * <p>“可选”的含义：默认关闭（DETERMINISTIC），图与向量只做召回增强，
 * 不改变事实权威（代码/数值表优先级、引用校验、发布边界仍然生效）。
 */
public enum RecallMode {
    DETERMINISTIC, GRAPH_VECTOR, HYBRID
}