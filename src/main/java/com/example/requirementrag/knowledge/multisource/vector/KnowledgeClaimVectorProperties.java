package com.example.requirementrag.knowledge.multisource.vector;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.Locale;

/**
 * 多源 Claim 向量投影配置（§8）。
 * <p>
 * Claim 向量投影开关默认关闭；语义增强默认开启，但仅在向量构建启用时参与召回文本生成。
 * Rollout 阶段：build-only → shadow query → internal fusion → limited prod → default-on。
 * 前缀：{@code app.rag.multi-source.claim-vector}
 */
@ConfigurationProperties("app.rag.multi-source.claim-vector")
public record KnowledgeClaimVectorProperties(
        boolean enabled,
        boolean buildEnabled,
        boolean candidateRetrievalEnabled,
        boolean shadowQueryEnabled,
        String alias,
        String projectionSchemaVersion,
        String textComposerVersion,
        int candidateLimit,
        int overFetchFactor,
        int batchSize,
        int representativeEvidenceLimit,
        int retainPhysicalCollections,
        String databasePath,
        String buildScope,
        int blockMaxChars,
        boolean semanticEnhancementEnabled,
        String semanticEnhancementModel
) {
    @ConstructorBinding
    public KnowledgeClaimVectorProperties {
        alias = textOr(alias, "knowledge_claims_live");
        // v2：点 ID 从 64-hex 改为 UUID（Qdrant v1.15+ 拒绝任意字符串点 ID）。
        // 投影 schema 属于契约的一部分——点 ID 算法变更必须换 schema，否则旧代际会被指纹命中而误复用。
        projectionSchemaVersion = textOr(projectionSchemaVersion, "knowledge-claim-vector-v2");
        textComposerVersion = textOr(textComposerVersion, "knowledge-claim-text-v2");
        databasePath = textOr(databasePath, "data/multi-source-knowledge.db");
        buildScope = normalizeBuildScope(buildScope);
        blockMaxChars = bounded(blockMaxChars, 4_000, 100_000, 24_000);
        // One gameplay card may span many facts. blockMaxChars bounds the Qdrant payload, not entity membership.
        semanticEnhancementModel = textOr(semanticEnhancementModel, "gpt-5.6-luna");
        candidateLimit = bounded(candidateLimit, 1, 1000, 200);
        overFetchFactor = bounded(overFetchFactor, 1, 10, 3);
        batchSize = bounded(batchSize, 1, 64, 32);
        representativeEvidenceLimit = bounded(representativeEvidenceLimit, 0, 10, 3);
        // 中（第七批 Review 2）：下限必须 ≥2——retain=1 时 switchAlias 尾部会立即删除前序集合，
        // markActive 失败补偿 rollbackAlias(前序目标) 与运维 /rollback 双双失去回滚目标，产生悬空 ACTIVE。
        retainPhysicalCollections = bounded(retainPhysicalCollections, 2, 10, 2);
    }

    /** Compatibility constructor for callers that use the pre-block projection contract. */
    public KnowledgeClaimVectorProperties(
            boolean enabled, boolean buildEnabled, boolean candidateRetrievalEnabled,
            boolean shadowQueryEnabled, String alias, String projectionSchemaVersion,
            String textComposerVersion, int candidateLimit, int overFetchFactor, int batchSize,
            int representativeEvidenceLimit, int retainPhysicalCollections, String databasePath,
            String buildScope) {
        this(enabled, buildEnabled, candidateRetrievalEnabled, shadowQueryEnabled, alias,
                projectionSchemaVersion, textComposerVersion, candidateLimit, overFetchFactor,
                batchSize, representativeEvidenceLimit, retainPhysicalCollections, databasePath,
                buildScope, 24_000, true, "gpt-5.6-luna");
    }

    /**
     * 返回指定项目+业务版本的独立 live alias（高：Review 2——所有项目/版本共用一个 alias 会跨项目泄漏）。
     * <p>每个 scope 独立 alias 与独立物理 collection 前缀，避免项目 B 构建后覆盖项目 A 的检索结果。
     * 形如 {@code knowledge_claims_live-<project>-<version>}。</p>
     */
    public String liveAlias(String projectId, String businessVersion) {
        return alias + "-" + scopeToken(projectId) + "-" + scopeToken(businessVersion);
    }

    /** 物理 collection 前缀（含 scope），供 retireOldCollections 只清理本 scope 的集合。 */
    public String physicalPrefix(String projectId, String businessVersion) {
        return liveAlias(projectId, businessVersion) + "-";
    }

    /**
     * 将 scope 分量规整为 Qdrant 集合名安全 token（高：Review 6——旧实现把所有非 ASCII
     * 字符替换为 '-' 并截断到 32 字符：项目甲/项目乙 都会变成 unknown，不同长 ID 也可能
     * 拥有相同前 32 字符，导致跨 scope 共用 alias/物理集合前缀而互相覆盖或误删）。
     * <p>新实现保留可读 token（截断到 24 字符），并附加原始 scope 的稳定 hash 后缀
     * （sha256 前 10 位 hex）——可读部分即使被归并为 unknown，hash 也保证不同原始值
     * 生成不同 token，一一映射。</p>
     */
    private static String scopeToken(String value) {
        String raw = value == null ? "unknown" : value.trim();
        String normalized = raw.toLowerCase()
                .replaceAll("[^a-z0-9._-]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (normalized.isEmpty()) {
            normalized = "unknown";
        }
        String readable = normalized.length() > 24 ? normalized.substring(0, 24) : normalized;
        return readable + "-" + stableHash(raw);
    }

    /** sha256 hex 前 10 位——稳定且不可逆，用于保证 scope token 一一映射。 */
    private static String stableHash(String value) {
        try {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                hex.append(String.format("%02x", bytes[i] & 0xff));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /** buildScope 合法值：ACTIVE_DOC（默认，投影 active manifest 单文档）/ ALL_PUBLISHED（投影全部已发布文档，与实体层同态）。 */
    public static final String SCOPE_ACTIVE_DOC = "ACTIVE_DOC";
    public static final String SCOPE_ALL_PUBLISHED = "ALL_PUBLISHED";

    /** 非法/空值一律回退 ACTIVE_DOC（默认行为不变，契约优先）。 */
    private static String normalizeBuildScope(String value) {
        if (value == null || value.isBlank()) {
            return SCOPE_ACTIVE_DOC;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return SCOPE_ALL_PUBLISHED.equals(normalized) ? SCOPE_ALL_PUBLISHED : SCOPE_ACTIVE_DOC;
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int bounded(int value, int min, int max, int fallback) {
        return value < min || value > max ? fallback : value;
    }
}
