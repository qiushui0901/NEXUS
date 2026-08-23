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
}