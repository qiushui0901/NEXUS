package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 0.9.6 多源 Claim 向量投影契约：Qdrant 点 payload、代际 manifest、输入记录、状态枚举与稳定告警码。
 * <p>
 * SQLite 是事实权威，Qdrant 是可弃投影。所有治理字段（authority/status/scope）在命中后从 SQLite 重新读取，
 * 不依赖 payload 值作为真相源。一个 Qdrant 点代表一个语义块（包含一个或多个 Claim，非一行 Evidence），
 * Representative Evidence IDs 仅作 payload 引用，完整 Claim/Evidence 在命中后从 SQLite 水化。
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
     * Qdrant payload 契约（§5.4）：一个点代表一个语义块，块内可包含多个 Claim。
     * claimIds 是召回展开索引，治理字段和完整 Evidence 在命中后仍从 SQLite 重新读取，不依赖 payload 值作为真相源。
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
            String textHash,
            String blockId,
            List<String> claimIds,
            String semanticText
    ) {
        /** Compatibility constructor for the previous one-Claim-per-point contract. */
        public KnowledgeClaimVectorPoint(String projectId, String businessVersion, String claimId,
                                         String documentVersionId, SourceType sourceType, Authority authority,
                                         String knowledgeStatus, String factKey, String subject, String predicate,
                                         String valueType, String unit, List<String> evidenceIds,
                                         String projectionGenerationId, String projectionSchemaVersion,
                                         String embeddingModel, String textHash) {
            this(projectId, businessVersion, claimId, documentVersionId, sourceType, authority,
                    knowledgeStatus, factKey, subject, predicate, valueType, unit, evidenceIds,
                    projectionGenerationId, projectionSchemaVersion, embeddingModel, textHash,
                    claimId, List.of(claimId), null);
        }

        public KnowledgeClaimVectorPoint {
            if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId 不能为空");
            if (businessVersion == null || businessVersion.isBlank()) throw new IllegalArgumentException("businessVersion 不能为空");
            if (claimId == null || claimId.isBlank()) throw new IllegalArgumentException("claimId 不能为空");
            if (sourceType == null) throw new IllegalArgumentException("sourceType 不能为空");
            if (authority == null) throw new IllegalArgumentException("authority 不能为空");
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            blockId = blockId == null || blockId.isBlank() ? claimId : blockId;
            claimIds = claimIds == null || claimIds.isEmpty() ? List.of(claimId)
                    : claimIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
            // 投影 schema 属契约的一部分：不允许缺失/空——缺失时旧实现默认回退 v1，升级后会造成旧代际
            // 伪装成新契约被检索。构建与解析两侧都必须显式携带当前 schema。
            if (projectionSchemaVersion == null || projectionSchemaVersion.isBlank()) {
                throw new IllegalArgumentException("projectionSchemaVersion 不能为空（投影契约必填）");
            }
            if (embeddingModel == null || embeddingModel.isBlank()) {
                throw new IllegalArgumentException("embeddingModel 不能为空（投影契约必填）");
            }
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
            String publishedAt,
            String buildScope
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
            // 旧代际（未记录 scope）视为 ACTIVE_DOC——保持与默认构建语义一致
            buildScope = buildScope == null || buildScope.isBlank()
                    ? "ACTIVE_DOC" : buildScope.trim().toUpperCase(java.util.Locale.ROOT);
        }
    }

    /**
     * 构建输入记录（§6.1）：一个 claim 对应一行。
     * active 代际只暴露 generation_input 表中记录的语义块首 Claim（block 由多个 claimIds 组成），
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
     * 确定性 Qdrant point ID（§5.2）：SHA-256(projectId | businessVersion | claimId | projectionSchemaVersion)
     * 前 16 字节 → 标准 UUID（版本 5 式，RFC 4122 变体位）。
     * 同一 claim 在同一投影 schema 下 ID 稳定；schema 升级生成新 ID，自然隔离新旧点。
     * <p>必须用标准 UUID/无符号整数：Qdrant v1.15+ 拒绝任意字符串点 ID
     * （"value ... is not a valid point ID"），64 位 hex 会被 400 拒绝——claim-vector 写入真实 Qdrant 时的关键约束。</p>
     */
    public static String deterministicPointId(String projectId, String businessVersion,
                                               String claimId, String projectionSchemaVersion) {
        return deterministicBlockPointId(projectId, businessVersion, claimId, projectionSchemaVersion);
    }

    /** Stable point ID for a semantic block; block IDs must not collide with claim IDs. */
    public static String deterministicBlockPointId(String projectId, String businessVersion,
                                                    String blockId, String projectionSchemaVersion) {
        String input = projectId + "|" + businessVersion + "|" + blockId + "|" + projectionSchemaVersion;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (digest[i] & 0xff);
            }
            for (int i = 8; i < 16; i++) {
                lsb = (lsb << 8) | (digest[i] & 0xff);
            }
            msb = (msb & 0xffffffffffff0fffL) | 0x0000000000005000L; // version 5
            lsb = (lsb & 0x3fffffffffffffffL) | 0x8000000000000000L; // variant 10 (RFC 4122)
            return new UUID(msb, lsb).toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /**
     * 输入指纹（§6.2）：SHA-256(sorted(blockId/首Claim|documentVersionId|updatedAt|textHash)
     * + schema + composer + embedding model + dim + scope + semantic enhancement identity)。
     * 输入顺序无关——按 claimId 排序后拼接。任何输入/schema/composer/model/dim/scope/增强配置变化生成新指纹。
     * <p>scope 参与指纹：同一 claim 集合在不同 build-scope 下（ACTIVE_DOC vs ALL_PUBLISHED）不共享代际，
     * 防止 findReusableGeneration 把不同投影范围的构建误复用。</p>
     */
    public static String computeInputFingerprint(
            List<ClaimVectorGenerationInput> inputs,
            String projectionSchemaVersion,
            String textComposerVersion,
            String embeddingModel,
            int embeddingDimension,
            String buildScope,
            boolean semanticEnhancementEnabled,
            String semanticEnhancementModel) {
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
        builder.append("dim=").append(embeddingDimension).append('\n');
        builder.append("scope=").append(buildScope == null || buildScope.isBlank() ? "ACTIVE_DOC" : buildScope).append('\n');
        builder.append("semanticEnhancementEnabled=").append(semanticEnhancementEnabled).append('\n');
        builder.append("semanticEnhancementModel=")
                .append(semanticEnhancementModel == null ? "" : semanticEnhancementModel);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(builder.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /** 兼容重载：未显式给定 scope 时按 ACTIVE_DOC（默认投影范围）计算指纹。 */
    public static String computeInputFingerprint(
            List<ClaimVectorGenerationInput> inputs,
            String projectionSchemaVersion,
            String textComposerVersion,
            String embeddingModel,
            int embeddingDimension) {
        return computeInputFingerprint(inputs, projectionSchemaVersion, textComposerVersion,
                embeddingModel, embeddingDimension, "ACTIVE_DOC", false, "gpt-5.6-luna");
    }

    /** Compatibility overload without semantic-enhancement identity. */
    public static String computeInputFingerprint(
            List<ClaimVectorGenerationInput> inputs,
            String projectionSchemaVersion,
            String textComposerVersion,
            String embeddingModel,
            int embeddingDimension,
            String buildScope) {
        return computeInputFingerprint(inputs, projectionSchemaVersion, textComposerVersion,
                embeddingModel, embeddingDimension, buildScope, false, "gpt-5.6-luna");
    }
}
