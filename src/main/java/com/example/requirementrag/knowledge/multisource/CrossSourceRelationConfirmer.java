package com.example.requirementrag.knowledge.multisource;

/**
 * 跨来源关系 LLM 语义确认：对确定性规则抽取出的 TEST_CASE→REQUIREMENT、
 * PARAMETER_TABLE→REQUIREMENT、DOUBT→REQUIREMENT 关系做二次语义确认。
 *
 * <p>实现在不确定/调用失败时必须 fail-open（返回 confirmed=true），避免规则基线被降级。
 */
public interface CrossSourceRelationConfirmer {

    /**
     * 确认一条跨来源关系是否成立。
     *
     * @param source       关系源 Claim（如测试用例/参数/存疑）
     * @param relationType 关系类型名称（VERIFIES/SUPPORTS/RAISES_DOUBT）
     * @param target       关系目标 Claim（需求）
     * @param evidence     关系来源证据位置
     * @return 确认结果；confirmed=false 表示关系被 LLM 判为不成立
     */
    Confirmation confirm(ClaimRef source, String relationType, ClaimRef target, String evidence);

    /** 关系参与方摘要。 */
    record ClaimRef(String id, String sourceType, String summary) {
    }

    /** LLM 确认结果。 */
    record Confirmation(boolean confirmed, String reason) {
    }
}