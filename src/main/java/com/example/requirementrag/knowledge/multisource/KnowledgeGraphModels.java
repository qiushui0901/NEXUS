package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;

import java.util.List;

/**
 * 跨源总实体关系图模型：把 PRD/DATA/QA/CASE（及代码）合并为统一实体与关系。
 */
public final class KnowledgeGraphModels {
    private KnowledgeGraphModels() {
    }

    /** 统一实体：实体名经规范化，绑定来源 Claim 与 Evidence。 */
    public record KnowledgeEntity(
            String entityId,
            String projectId,
            String version,
            String name,
            String normalizedName,
            String entityType,
            SourceType sourceType,
            String summary,
            String evidenceId,
            List<String> sourceClaimIds,
            String createdAt,
            String updatedAt
    ) {
        public KnowledgeEntity {
            if (entityId == null || entityId.isBlank()) throw new IllegalArgumentException("entityId 不能为空");
            if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId 不能为空");
            if (version == null || version.isBlank()) throw new IllegalArgumentException("version 不能为空");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name 不能为空");
            sourceClaimIds = sourceClaimIds == null ? List.of() : List.copyOf(sourceClaimIds);
            String now = java.time.Instant.now().toString();
            createdAt = createdAt == null ? now : createdAt;
            updatedAt = updatedAt == null ? createdAt : updatedAt;
        }
    }

    /** 统一实体关系：带状态/置信度/抽取方式/Evidence。 */
    public record KnowledgeEntityRelation(
            String relationId,
            String projectId,
            String version,
            String sourceEntityId,
            String targetEntityId,
            String relationType,
            String status,
            Double confidence,
            String extractionMethod,
            List<String> evidenceIds,
            String createdAt,
            String updatedAt
    ) {
        public KnowledgeEntityRelation {
            if (relationId == null || relationId.isBlank()) throw new IllegalArgumentException("relationId 不能为空");
            if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId 不能为空");
            if (version == null || version.isBlank()) throw new IllegalArgumentException("version 不能为空");
            if (sourceEntityId == null || sourceEntityId.isBlank()) throw new IllegalArgumentException("sourceEntityId 不能为空");
            if (targetEntityId == null || targetEntityId.isBlank()) throw new IllegalArgumentException("targetEntityId 不能为空");
            if (relationType == null || relationType.isBlank()) throw new IllegalArgumentException("relationType 不能为空");
            status = status == null || status.isBlank() ? "RULE_PROPOSED" : status;
            extractionMethod = extractionMethod == null || extractionMethod.isBlank() ? "RULE" : extractionMethod;
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            String now = java.time.Instant.now().toString();
            createdAt = createdAt == null ? now : createdAt;
            updatedAt = updatedAt == null ? createdAt : updatedAt;
        }
    }
}