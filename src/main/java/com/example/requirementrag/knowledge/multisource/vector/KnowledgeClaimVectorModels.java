package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * 0.9.6 多源 Claim 向量投影契约：Qdrant 点 payload、代际 manifest、输入记录、状态枚举与稳定告警码。
 * <p>
 * SQLite 是事实权威，Qdrant 是可弃投影。所有治理字段（authority/status/scope）在命中后从 SQLite 重新读取，
 * 不依赖 payload 值作为真相源。一个 Qdrant 点代表一个 Claim（非一行 Evidence），Representative Evidence IDs
 * 仅作 payload 引用，完整 Evidence 在命中后从 SQLite 水化。
 * <p>
 * 方案：docs/multi-source-claim-vector-retrieval-development-plan-0.9.6.md §5 §6
 */
public final class KnowledgeClaimVectorModels {

    private KnowledgeClaimVectorModels() {}

    /** 向量投影代际状态。 */
    public enum GenerationStatus {
        /** 正在嵌入/写入 Qdrant 物理集合。 */
        BUILDING,
        /** 正在校验点数/payload/维度/scope。 */
        VERIFYING,
        /** 构建+校验完成，但未通过 alias 切换接管线上。 */
        SUCCESS,
        /** 构建或校验失败，alias 不变。 */
        FAILED,
        /** 已通过 alias 切换接管线上检索。同一 projectId+businessVersion 至多一个 ACTIVE。 */
        ACTIVE,
        /** 曾为 ACTIVE，被新代际替换，保留供回滚。 */
        RETIRED
    }

    /** 稳定告警码：不泄漏 Qdrant URL / SQL / 异常文本。 */
    public static final class WarningCode {
        public static final String DISABLED              = "KNOWLEDGE_CLAIM_VECTOR_DISABLED";
        public static final String UNAVAILABLE           = "KNOWLEDGE_CLAIM_VECTOR_UNAVAILABLE";
        public static final String GENERATION_MISSING    = "KNOWLEDGE_CLAIM_VECTOR_GENERATION_MISSING";
        public static final String GENERATION_STALE      = "KNOWLEDGE_CLAIM_VECTOR_GENERATION_STALE";
        public static final String TRUNCATED             = "KNOWLEDGE_CLAIM_VECTOR_TRUNCATED";
        public static final String HYDRATION_INCOMPLETE  = "KNOWLEDGE_CLAIM_VECTOR_HYDRATION_INCOMPLETE";
        public static final String SCOPE_MISMATCH        = "KNOWLEDGE_CLAIM_VECTOR_SCOPE_MISMATCH";
        public static final String BUDGET_EXCEEDED       = "KNOWLEDGE_CLAIM_VECTOR_BUDGET_EXCEEDED";
        public static final String BUILD_FAILED          = "KNOWLEDGE_CLAIM_VECTOR_BUILD_FAILED";
        public static final String VERIFICATION_FAILED   = "KNOWLEDGE_CLAIM_VECTOR_VERIFICATION_FAILED";
        /** 高（Review 6）：Qdrant alias 切换失败——代际保持非 ACTIVE，旧 ACTIVE 与旧 alias 不变。 */
        public static final String ALIAS_SWITCH_FAILED   = "KNOWLEDGE_CLAIM_VECTOR_ALIAS_SWITCH_FAILED";

        private WarningCode() {}
    }

    /**
     * Qdrant payload 契约（§5.4）：一个点代表一个 Claim（非一行 Evidence）。
     * Representative Evidence IDs 至多 {@code representativeEvidenceLimit} 个（默认 3），按 role+ID 排序。
     * 治理字段在检索后从 SQLite 重新读取，不依赖 payload 值作为真相源。
     */
    public record KnowledgeClaimVectorPoint(
            String projectId,
            String businessVersion,
            String claimId,
            String documentVersionId,
            SourceType sourceType,
            Authority authority,
            String knowledgeStatus,
            String factKey,
            String subject,
            String predicate,
            String valueType,
            String unit,
            List<String> evidenceIds,
            String projectionGenerationId,
            String projectionSchemaVersion,
            String embeddingModel,
            String textHash
    ) {
        public KnowledgeClaimVectorPoint {
            if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId 不能为空");
            if (businessVersion == null || businessVersion.isBlank()) throw new IllegalArgumentException("businessVersion 不能为空");
            if (claimId == null || claimId.isBlank()) throw new IllegalArgumentException("claimId 不能为空");
            if (sourceType == null) throw new IllegalArgumentException("sourceType 不能为空");
            if (authority == null) throw new IllegalArgumentException("authority 不能为空");
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            projectionSchemaVersion = projectionSchemaVersion == null || projectionSchemaVersion.isBlank()
                    ? "knowledge-claim-vector-v1" : projectionSchemaVersion;
        }
    }

    /** 代际 manifest（§6.1）：一次构建投影的不可变记录。 */
    public record ClaimVectorGenerationManifest(
            String generationId,
            String projectId,
            String businessVersion,
            String inputFingerprint,
            String projectionSchemaVersion,
            String textComposerVersion,
            String embeddingModel,
            int embeddingDimension,
            String physicalCollection,
            GenerationStatus status,
            int expectedPointCount,
            int indexedPointCount,
            String warningsJson,
            String startedAt,
            String finishedAt,
            String publishedAt
    ) {
        public ClaimVectorGenerationManifest {
            if (generationId == null || generationId.isBlank()) throw new IllegalArgumentException("generationId 不能为空");
            if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId 不能为空");
            if (businessVersion == null || businessVersion.isBlank()) throw new IllegalArgumentException("businessVersion 不能为空");
            if (inputFingerprint == null || inputFingerprint.isBlank()) throw new IllegalArgumentException("inputFingerprint 不能为空");
            if (status == null) throw new IllegalArgumentException("status 不能为空");
            warningsJson = warningsJson == null || warningsJson.isBlank() ? "[]" : warningsJson;
            indexedPointCount = Math.max(0, indexedPointCount);
            expectedPointCount = Math.max(0, expectedPointCount);
        }
    }

    /**
     * 构建输入记录（§6.1）：一个 claim 对应一行。
     * active 代际只暴露 generation_input 表中记录的 claim（source_chunk_id + text_hash），
     * 已删除/过期窗口的旧成功记录不可被重新激活。
     */
    public record ClaimVectorGenerationInput(
            String generationId,
            String claimId,
            String documentVersionId,
            String textHash,
            String updatedAt
    ) {
        public ClaimVectorGenerationInput {
            if (generationId == null || generationId.isBlank()) throw new IllegalArgumentException("generationId 不能为空");
            if (claimId == null || claimId.isBlank()) throw new IllegalArgumentException("claimId 不能为空");
            if (documentVersionId == null || documentVersionId.isBlank()) throw new IllegalArgumentException("documentVersionId 不能为空");
            if (textHash == null || textHash.isBlank()) throw new IllegalArgumentException("textHash 不能为空");
        }
    }

    /**
     * 确定性 Qdrant point ID（§5.2）：SHA-256(projectId | businessVersion | claimId | projectionSchemaVersion)。
     * 同一 claim 在同一投影 schema 下 ID 稳定；schema 升级生成新 ID，自然隔离新旧点。
     */
    public static String deterministicPointId(String projectId, String businessVersion,
                                               String claimId, String projectionSchemaVersion) {
        String input = projectId + "|" + businessVersion + "|" + claimId + "|" + projectionSchemaVersion;
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /**
     * 输入指纹（§6.2）：SHA-256(sorted(claimId|documentVersionId|updatedAt|textHash) + schema + composer + model + dim)。
     * 输入顺序无关——按 claimId 排序后拼接。任何输入/schema/composer/model/dim 变化生成新指纹。
     */
    public static String computeInputFingerprint(
            List<ClaimVectorGenerationInput> inputs,
            String projectionSchemaVersion,
            String textComposerVersion,
            String embeddingModel,
            int embeddingDimension) {
        List<ClaimVectorGenerationInput> sorted = inputs.stream()
                .sorted(java.util.Comparator.comparing(ClaimVectorGenerationInput::claimId))
                .toList();
        StringBuilder builder = new StringBuilder();
        for (ClaimVectorGenerationInput input : sorted) {
            builder.append(input.claimId()).append('|')
                    .append(input.documentVersionId()).append('|')
                    .append(input.updatedAt() == null ? "" : input.updatedAt()).append('|')
                    .append(input.textHash()).append('\n');
        }
        builder.append("schema=").append(projectionSchemaVersion).append('\n');
        builder.append("composer=").append(textComposerVersion).append('\n');
        builder.append("model=").append(embeddingModel).append('\n');
        builder.append("dim=").append(embeddingDimension);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(builder.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
