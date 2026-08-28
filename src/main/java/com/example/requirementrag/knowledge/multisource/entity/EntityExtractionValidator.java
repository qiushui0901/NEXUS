package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.QuestionExtractionRaw;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.SourceExtractionRaw;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 实体提取输出的结构化校验（dev md §6.2 六条校验）。
 *
 * <p>校验不信任模型输出：实体非空、数量上限、facts 的 sourceClaimId 必须属于输入批次、
 * relationType 白名单、受限选择只能选系统候选。校验失败抛出 {@link EntityExtractionException}。
 */
@Component
public final class EntityExtractionValidator {

    private static final Set<String> ALLOWED_RELATION_TYPES = Set.of(
            "SUPPORTS", "VERIFIES", "IMPLEMENTED_BY", "RAISES_DOUBT",
            "SUPERSEDES", "REFINES", "REPEALS", "SAME_FACT", "RELATED_TO");

    private static final Set<String> ALLOWED_INTENTS = Set.of(
            "GENERAL", "CURRENT_STATE", "NUMERIC_VALUE", "IMPLEMENTATION",
            "HISTORY", "VALIDATION", "CONSISTENCY");

    private final EntityExtractionProperties properties;

    public EntityExtractionValidator(EntityExtractionProperties properties) {
        this.properties = properties;
    }

    /** 校验问题实体提取输出。unknownEntities 为系统候选集合；空表示不限制。 */
    /** 校验问题实体提取输出（结构化：实体非空、数量上限、版本有界）。
     * 实体能否解析到真实概念由 QuestionEntityAnalyzer 做 resolve-or-drop，
     * 防止伪造 ID 的机制不在校验层硬拒名字（名字可能匹配别名/成员名）。 */
    public QuestionExtractionRaw validateQuestion(QuestionExtractionRaw raw) {
        if (raw == null || raw.entities() == null) {
            return new QuestionExtractionRaw(List.of(), null, List.of());
        }
        if (raw.entities().size() > properties.maxMentionsPerQuery()) {
            throw new EntityExtractionException("ENTITY_BUDGET_EXCEEDED",
                    "问题实体数量超过上限 " + properties.maxMentionsPerQuery());
        }
        List<com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityName> valid = raw.entities().stream()
                .filter(e -> e != null && e.name() != null && !e.name().isBlank())
                .toList();
        String intent = raw.intent() == null ? null : raw.intent().trim().toUpperCase(java.util.Locale.ROOT);
        return new QuestionExtractionRaw(valid,
                intent != null && ALLOWED_INTENTS.contains(intent) ? intent : null,
                boundedVersions(raw.versions()));
    }

    /** 校验来源提取输出。inputClaimIds 必须非空，fact.sourceClaimId 必须属于输入批次。 */
    public SourceExtractionRaw validateSource(SourceExtractionRaw raw, Set<String> inputClaimIds) {
        if (raw == null || raw.entities() == null
                || raw.facts() == null || raw.relations() == null) {
            return SourceExtractionRaw.EMPTY;
        }
        List<?> entities = raw.entities().stream()
                .filter(e -> e != null && e.name() != null && !e.name().isBlank()).toList();
        if (entities.size() > properties.maxEntitiesPerSourceBatch()) {
            throw new EntityExtractionException("ENTITY_BUDGET_EXCEEDED",
                    "来源实体数量超过上限 " + properties.maxEntitiesPerSourceBatch());
        }
        if (raw.facts().size() > properties.maxFactsPerSourceBatch()) {
            throw new EntityExtractionException("ENTITY_BUDGET_EXCEEDED",
                    "来源事实数量超过上限 " + properties.maxFactsPerSourceBatch());
        }
        if (raw.relations().size() > properties.maxRelationsPerSourceBatch()) {
            throw new EntityExtractionException("ENTITY_BUDGET_EXCEEDED",
                    "来源关系数量超过上限 " + properties.maxRelationsPerSourceBatch());
        }
        for (var fact : raw.facts()) {
            if (fact == null || fact.sourceClaimId() == null || fact.sourceClaimId().isBlank()) {
                throw new EntityExtractionException("ENTITY_CLAIM_INVALID", "来源事实缺少 sourceClaimId");
            }
            if (!inputClaimIds.contains(fact.sourceClaimId())) {
                throw new EntityExtractionException("ENTITY_CLAIM_INVALID",
                        "来源事实引用了输入批次之外的 Claim: " + fact.sourceClaimId());
            }
        }
        for (var relation : raw.relations()) {
            if (relation == null || relation.relationType() == null
                    || !ALLOWED_RELATION_TYPES.contains(relation.relationType().trim().toUpperCase(java.util.Locale.ROOT))) {
                throw new EntityExtractionException("ENTITY_RELATION_INVALID",
                        "非法关系类型: " + (relation == null ? "null" : relation.relationType()));
            }
        }
        return raw;
    }

    /** 受限选择：entityId 必须存在于候选集。 */
    public void validateSelection(String entityId, Set<String> allowedIds) {
        if (entityId == null || entityId.isBlank()) {
            return; // 允许 LLM 返回 null（都不匹配），由调用方决定 NEEDS_REVIEW
        }
        if (!allowedIds.contains(entityId)) {
            throw new EntityExtractionException("ENTITY_UNKNOWN",
                    "LLM 选择了未注册候选实体: " + entityId);
        }
    }

    private List<String> boundedVersions(List<String> versions) {
        if (versions == null) return List.of();
        return versions.stream().filter(v -> v != null && !v.isBlank())
                .limit(16).toList();
    }
}