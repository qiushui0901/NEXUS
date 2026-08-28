package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityQueryPlan;

import java.util.List;

/** 实体中心证据查询（dev md §8.4）响应模型。factAssessment 为 Phase 4 占位。 */
public final class EntityEvidenceModels {

    private EntityEvidenceModels() {
    }

    /** 单条事实引用（claim/代码成员级）。location 用于代码与表格的定位信息（如 文件:起-止行）。 */
    public record FactRef(
            String claimId,
            String externalId,
            String sourceType,
            String subject,
            String objectValue,
            String unit,
            String businessVersion,
            List<String> evidenceIds,
            String location,
            String factKey,
            String predicate,
            String excerpt
    ) {
        public FactRef {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            excerpt = excerpt == null ? null : excerpt.length() > 2_000 ? excerpt.substring(0, 2_000) : excerpt;
        }

        /** Compatibility constructor for callers that only need display fields. */
        public FactRef(String claimId, String externalId, String sourceType, String subject,
                       String objectValue, String unit, String businessVersion,
                       List<String> evidenceIds, String location) {
            this(claimId, externalId, sourceType, subject, objectValue, unit, businessVersion,
                    evidenceIds, location, null, null, null);
        }

        /** Compatibility constructor for callers that already provide fact identity fields. */
        public FactRef(String claimId, String externalId, String sourceType, String subject,
                       String objectValue, String unit, String businessVersion,
                       List<String> evidenceIds, String location, String factKey, String predicate) {
            this(claimId, externalId, sourceType, subject, objectValue, unit, businessVersion,
                    evidenceIds, location, factKey, predicate, null);
        }
    }

    /** 当前事实分区：代码 / 数值表 / 测试结果（互不混合）。 */
    public record CurrentFacts(
            List<FactRef> code,
            List<FactRef> parameterTables,
            List<FactRef> testResults
    ) {
    }

    /** 时间轴上一个业务版本的事实块。 */
    public record VersionFactBlock(
            String businessVersion,
            List<FactRef> requirements,
            List<FactRef> parameterTables,
            List<FactRef> tests
    ) {
    }

    /** 关系视图（保留生命周期状态位）。 */
    public record RelationView(
            String relationId,
            String relationType,
            String sourceClaimId,
            String targetClaimId,
            String matchMethod,
            String status,
            Double confidence,
            String evidenceId
    ) {
    }

    /** 确定性冲突：同 factKey 不同取值。 */
    public record ConflictView(
            String factKey,
            String subject,
            List<String> values,
            String status
    ) {
    }

    /** 实体视图：当前事实 + 全版本时间轴 + 关系 + 冲突 + 告警。 */
    public record EntityView(
            String entityId,
            String canonicalName,
            List<String> aliases,
            CurrentFacts currentFacts,
            List<VersionFactBlock> timeline,
            List<RelationView> relations,
            List<ConflictView> conflicts,
            List<String> warnings
    ) {
    }

    /** 事实评估条目：type（来源角色或偏差类型）/value/sourceType/status。 */
    public record AssessmentItem(String type, String value, String sourceType, String status) {
    }

    /** 事实评估：currentBehavior/currentValues/validation/requirementTarget/implementationGaps 分区。 */
    public record FactAssessment(
            List<AssessmentItem> currentBehavior,
            List<AssessmentItem> currentValues,
            List<AssessmentItem> validation,
            List<AssessmentItem> requirementTarget,
            List<AssessmentItem> implementationGaps
    ) {
        /** 空骨架。 */
        public static final FactAssessment EMPTY =
                new FactAssessment(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** 引用（证据去重后的可回源列表）。 */
    public record Citation(
            String claimId,
            String sourceType,
            String businessVersion,
            String evidenceId
    ) {
    }

    /** entity-search 响应。 */
    public record EntitySearchResponse(
            String query,
            EntityQueryPlan plan,
            List<EntityView> entities,
            FactAssessment factAssessment,
            List<Citation> citations,
            List<String> warnings
    ) {
    }

    /** 向量补召回命中（Claim 级，Qdrant 代际存在时可选返回）。 */
    public record VectorHit(String claimId, String subject, String sourceType) {
    }

    /** 图/向量增强召回方式的响应：确定性证据 + 局部图 + 可选向量补召回。
     * evidence 为确定性检索结果（种子实体的事实/引用），entities 为扩展后的实体集。 */
    public record EntityRecallResponse(
            String query,
            EntityQueryPlan plan,
            List<EntityView> entities,
            FactAssessment factAssessment,
            List<Citation> citations,
            List<String> warnings,
            String recallMode,
            EntitySearchResponse evidence,
            EntityGraphExpansionService.RelatedGraph graph,
            List<VectorHit> vectorHits,
            int relatedEntityCount
    ) {
        public EntityRecallResponse {
            vectorHits = vectorHits == null ? List.of() : vectorHits;
        }
    }

    /** 来源类型判断辅助（避免前端依赖内部枚举名）。 */
    public static boolean isParameter(SourceType type) {
        return type == SourceType.PARAMETER_TABLE;
    }

    public static boolean isRequirement(SourceType type) {
        return type == SourceType.REQUIREMENT || type == SourceType.REQUIREMENT_SEMANTIC;
    }

    public static boolean isTest(SourceType type) {
        return type == SourceType.TEST_CASE || type == SourceType.TEST_RESULT;
    }
}