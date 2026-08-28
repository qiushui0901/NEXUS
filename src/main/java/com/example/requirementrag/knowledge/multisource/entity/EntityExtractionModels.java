package com.example.requirementrag.knowledge.multisource.entity;

import java.util.List;

/**
 * 实体中心检索——实体提取与归一化的共享模型。
 *
 * <p>规则优先、LLM 辅助：来源事实先由确定性解析器落库；LLM 只输出结构化候选（实体/事实/关系），
 * 并接受“候选 entityId 受限选择”。所有 LLM 提议的别名/关系默认 status=PROPOSED，不参与确认匹配。
 */
public final class EntityExtractionModels {

    private EntityExtractionModels() {
    }

    /** 查询意图（规则推导，LLM 仅辅助补召回）。 */
    public enum QueryIntent {
        GENERAL, CURRENT_STATE, NUMERIC_VALUE, IMPLEMENTATION, HISTORY, VALIDATION, CONSISTENCY
    }

    /** mention 状态：RESOLVED=命中唯一实体；CANDIDATE=候选；NEEDS_REVIEW=歧义/低置信待人工。 */
    public enum MentionStatus {
        RESOLVED, CANDIDATE, NEEDS_REVIEW
    }

    /** 别名来源：明确出现在来源 / 规则归一化 / LLM 提议 / 人工确认。 */
    public enum AliasOrigin {
        SOURCE_EXPLICIT, RULE_NORMALIZED, LLM_PROPOSED, HUMAN_CONFIRMED
    }

    /** 别名状态：CONFIRMED 参与精确匹配；PROPOSED 只做召回线索，不影响确认匹配。 */
    public enum AliasStatus {
        CONFIRMED, PROPOSED
    }

    /** 匹配方法：确定性解析链或 LLM 受限选择。 */
    public enum MatchMethod {
        NORMALIZED_EXACT, CONFIRMED_ALIAS, MEMBER_NAME, FACT_KEY, CODE_SYMBOL, VECTOR_HINT, LLM_SELECTED, UNRESOLVED
    }

    /** 问题中的实体提及。 */
    public record EntityMention(
            String text,
            String entityId,
            String canonicalName,
            MatchMethod matchMethod,
            Double confidence,
            MentionStatus status
    ) {
    }

    /** 问题解析计划（dev md §7.1）。 */
    public record EntityQueryPlan(
            String projectId,
            String originalQuery,
            List<EntityMention> mentions,
            QueryIntent intent,
            List<String> requestedVersions,
            boolean includeHistory,
            boolean asksCurrentState,
            boolean asksImplementation,
            boolean asksNumericValue
    ) {
    }

    /** 解析候选（未命中唯一实体时返回给 LLM 受限选择或标记 NEEDS_REVIEW）。 */
    public record ResolutionCandidate(
            String entityId,
            String canonicalName,
            String canonicalKey,
            MatchMethod matchMethod,
            Double confidence
    ) {
    }

    /** 实体解析结果：命中的 + 候选（NEEDS_REVIEW）+ 降级告警。 */
    public record EntityResolution(
            List<EntityMention> resolved,
            List<ResolutionCandidate> candidates,
            boolean llmUsed,
            List<String> warnings
    ) {
    }

    /** LLM 提议的别名（不自动全局生效）。 */
    public record AliasProposal(
            String entityId,
            String alias,
            AliasOrigin origin,
            Double confidence,
            String evidenceId
    ) {
    }

    /** LLM 提议的关系（status 默认 PROPOSED）。 */
    public record RelationProposal(
            String sourceClaimId,
            String targetClaimId,
            String relationType,
            String matchMethod,
            Double confidence,
            List<String> evidenceIds,
            String status
    ) {
    }

    // ===== LLM 结构化输出（JSON → record） =====

    /** 问题实体提取输出。 */
    public record QuestionExtractionRaw(
            List<EntityName> entities,
            String intent,
            List<String> versions
    ) {
    }

    /** 实体候选（LLM 输出），name 必须能解析到系统候选。 */
    public record EntityName(String name, List<String> aliases, String type, Double confidence) {
    }

    /** 来源实体提取输出（dev md §6.2）。 */
    public record SourceExtractionRaw(
            List<SourceEntityRaw> entities,
            List<SourceFactRaw> facts,
            List<SourceRelationRaw> relations
    ) {
        /** 空结果：实体/事实/关系均为空列表。 */
        public static final SourceExtractionRaw EMPTY =
                new SourceExtractionRaw(List.of(), List.of(), List.of());

        public SourceExtractionRaw {
            entities = entities == null ? List.of() : List.copyOf(entities);
            facts = facts == null ? List.of() : List.copyOf(facts);
            relations = relations == null ? List.of() : List.copyOf(relations);
        }
    }

    public record SourceEntityRaw(
            String name, List<String> aliases, String type, String description, Double confidence) {
    }

    /** 来源事实候选：sourceClaimId 必须真实存在且属于输入批次。 */
    public record SourceFactRaw(
            String entityName, String predicate, String value, String unit,
            String sourceClaimId, Double confidence) {
    }

    /** 来源关系候选：两端必须能解析到提议实体或代码符号。 */
    public record SourceRelationRaw(
            String sourceEntityName, String targetName, String relationType, Double confidence) {
    }
}
