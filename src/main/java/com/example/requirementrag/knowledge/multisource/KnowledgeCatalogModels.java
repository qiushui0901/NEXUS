package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;

/**
 * 0.9.3 多源知识统一资料目录领域模型：
 * 原始资料（Document）、不可变版本（DocumentVersion）与结构化 Evidence。
 *
 * <p>这些记录是“可审计主数据”的第一步：任何 Claim 都应能定位到一份资料版本和原始位置。
 */
public final class KnowledgeCatalogModels {
    private KnowledgeCatalogModels() {
    }

    /** 逻辑原始资料登记：只存元数据，不存正文。 */
    public record KnowledgeDocument(
            String documentId,
            String projectId,
            SourceType sourceType,
            String logicalName,
            String originalName,
            String storageUri,
            Authority authority,
            String createdAt
    ) {
        public KnowledgeDocument {
            if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("documentId 不能为空");
            if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId 不能为空");
            if (sourceType == null) throw new IllegalArgumentException("sourceType 不能为空");
            if (logicalName == null || logicalName.isBlank()) throw new IllegalArgumentException("logicalName 不能为空");
            if (storageUri == null || storageUri.isBlank()) throw new IllegalArgumentException("storageUri 不能为空");
            authority = authority == null ? Authority.PRIMARY : authority;
            createdAt = createdAt == null ? java.time.Instant.now().toString() : createdAt;
        }
    }

    /** 不可变资料版本：同内容同业务版本幂等复用，不覆盖历史。 */
    public record KnowledgeDocumentVersion(
            String documentVersionId,
            String documentId,
            String projectId,
            String businessVersion,
            String contentHash,
            String parserVersion,
            String extractionVersion,
            String sourceCommitSha,
            String status,
            String importedAt,
            String publishedAt
    ) {
        public KnowledgeDocumentVersion {
            if (documentVersionId == null || documentVersionId.isBlank()) {
                throw new IllegalArgumentException("documentVersionId 不能为空");
            }
            if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("documentId 不能为空");
            if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId 不能为空");
            if (businessVersion == null || businessVersion.isBlank()) {
                throw new IllegalArgumentException("businessVersion 不能为空");
            }
            if (contentHash == null || contentHash.isBlank()) throw new IllegalArgumentException("contentHash 不能为空");
            parserVersion = parserVersion == null || parserVersion.isBlank() ? "v1" : parserVersion;
            extractionVersion = extractionVersion == null || extractionVersion.isBlank() ? "v1" : extractionVersion;
            status = status == null || status.isBlank() ? "DRAFT" : status;
            importedAt = importedAt == null ? java.time.Instant.now().toString() : importedAt;
        }
    }

    /** 结构化 Evidence：原始位置 + 摘录摘要；ID 由服务端稳定生成，禁止 LLM 伪造。 */
    public record KnowledgeEvidence(
            String evidenceId,
            String documentVersionId,
            String projectId,
            SourceType sourceType,
            String locator,
            String excerpt,
            String excerptHash,
            Integer startLine,
            Integer endLine,
            String sheetName,
            Integer rowNumber,
            String columnRange,
            String repositoryId,
            String commitSha,
            String symbolName,
            String createdAt
    ) {
        public KnowledgeEvidence {
            if (evidenceId == null || evidenceId.isBlank()) throw new IllegalArgumentException("evidenceId 不能为空");
            if (documentVersionId == null || documentVersionId.isBlank()) {
                throw new IllegalArgumentException("documentVersionId 不能为空");
            }
            if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId 不能为空");
            if (sourceType == null) throw new IllegalArgumentException("sourceType 不能为空");
            if (locator == null || locator.isBlank()) throw new IllegalArgumentException("locator 不能为空");
            if (excerpt == null) excerpt = "";
            if (excerptHash == null || excerptHash.isBlank()) excerptHash = String.valueOf(excerpt.hashCode());
            createdAt = createdAt == null ? java.time.Instant.now().toString() : createdAt;
        }
    }

    /** 业务表到 catalog 的可回查关联。 */
    public record CatalogReference(String documentVersionId, String evidenceId) {
    }

    /** Claim ↔ Evidence 关联角色。 */
    public enum ClaimEvidenceRole {
        SUPPORTS,
        CONTRADICTS,
        CONTEXT,
        RESOLUTION
    }

    /** 来源无关的统一 Claim 主记录：claim_id 与业务表主键一致，便于回查。 */
    public record KnowledgeClaimRecord(
            String claimId,
            String projectId,
            String documentVersionId,
            SourceType sourceType,
            Authority authority,
            String factKey,
            String subject,
            String predicate,
            String objectValue,
            String valueType,
            String unit,
            String status,
            Double confidence,
            String effectiveFrom,
            String effectiveTo,
            String extractionMethod,
            String extractionRunId,
            String createdAt,
            String updatedAt
    ) {
        public KnowledgeClaimRecord {
            if (claimId == null || claimId.isBlank()) throw new IllegalArgumentException("claimId 不能为空");
            if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId 不能为空");
            if (documentVersionId == null || documentVersionId.isBlank()) {
                throw new IllegalArgumentException("documentVersionId 不能为空");
            }
            if (sourceType == null) throw new IllegalArgumentException("sourceType 不能为空");
            if (authority == null) throw new IllegalArgumentException("authority 不能为空");
            if (subject == null || subject.isBlank()) throw new IllegalArgumentException("subject 不能为空");
            if (predicate == null) predicate = "";
            if (factKey == null || factKey.isBlank()) throw new IllegalArgumentException("factKey 不能为空");
            status = status == null || status.isBlank() ? "SUPPORTED" : status;
            extractionMethod = extractionMethod == null || extractionMethod.isBlank() ? "RULE" : extractionMethod;
            String now = java.time.Instant.now().toString();
            createdAt = createdAt == null ? now : createdAt;
            updatedAt = updatedAt == null ? createdAt : updatedAt;
        }
    }

    /** Claim 与 Evidence 关联记录。 */
    public record KnowledgeClaimEvidence(
            String claimId,
            String evidenceId,
            ClaimEvidenceRole role,
            String createdAt
    ) {
        public KnowledgeClaimEvidence {
            if (claimId == null || claimId.isBlank()) throw new IllegalArgumentException("claimId 不能为空");
            if (evidenceId == null || evidenceId.isBlank()) throw new IllegalArgumentException("evidenceId 不能为空");
            role = role == null ? ClaimEvidenceRole.SUPPORTS : role;
            createdAt = createdAt == null ? java.time.Instant.now().toString() : createdAt;
        }
    }

    /** 统一关系：带状态、置信度、证据与确认审计。 */
    public record KnowledgeRelation(
            String relationId,
            String projectId,
            String version,
            String sourceClaimId,
            String targetClaimId,
            String relationType,
            String status,
            Double confidence,
            String evidenceId,
            String extractionMethod,
            String confirmationMethod,
            String confirmationReason,
            String createdAt,
            String updatedAt
    ) {
        public KnowledgeRelation {
            if (relationId == null || relationId.isBlank()) throw new IllegalArgumentException("relationId 不能为空");
            if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId 不能为空");
            if (version == null || version.isBlank()) throw new IllegalArgumentException("version 不能为空");
            if (sourceClaimId == null || sourceClaimId.isBlank()) throw new IllegalArgumentException("sourceClaimId 不能为空");
            if (targetClaimId == null || targetClaimId.isBlank()) throw new IllegalArgumentException("targetClaimId 不能为空");
            if (relationType == null || relationType.isBlank()) throw new IllegalArgumentException("relationType 不能为空");
            status = status == null || status.isBlank() ? "RULE_PROPOSED" : status;
            extractionMethod = extractionMethod == null || extractionMethod.isBlank() ? "RULE" : extractionMethod;
            String now = java.time.Instant.now().toString();
            createdAt = createdAt == null ? now : createdAt;
            updatedAt = updatedAt == null ? createdAt : updatedAt;
        }
    }

    /** 抽取运行审计：单次解析/抽取/关系生成任务账本。 */
    public record ExtractionRun(
            String extractionRunId,
            String projectId,
            String documentVersionId,
            String parserName,
            String parserVersion,
            String modelName,
            String promptVersion,
            String inputHash,
            String outputHash,
            String status,
            Integer promptTokens,
            Integer completionTokens,
            String errorMessage,
            String startedAt,
            String finishedAt
    ) {
        public ExtractionRun {
            if (extractionRunId == null || extractionRunId.isBlank()) throw new IllegalArgumentException("extractionRunId 不能为空");
            if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId 不能为空");
            if (documentVersionId == null || documentVersionId.isBlank()) {
                throw new IllegalArgumentException("documentVersionId 不能为空");
            }
            if (parserName == null || parserName.isBlank()) throw new IllegalArgumentException("parserName 不能为空");
            parserVersion = parserVersion == null || parserVersion.isBlank() ? "v1" : parserVersion;
            inputHash = inputHash == null || inputHash.isBlank() ? "" : inputHash;
            status = status == null || status.isBlank() ? "RUNNING" : status;
            startedAt = startedAt == null ? java.time.Instant.now().toString() : startedAt;
        }
    }
}