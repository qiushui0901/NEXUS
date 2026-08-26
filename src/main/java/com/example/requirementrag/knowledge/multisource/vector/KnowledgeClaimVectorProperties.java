package com.example.requirementrag.knowledge.multisource.vector;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多源 Claim 向量投影配置（§8）。
 * <p>
 * 所有开关默认关闭。Rollout 阶段：build-only → shadow query → internal fusion → limited prod → default-on。
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
        String databasePath
) {
    public KnowledgeClaimVectorProperties {
        alias = textOr(alias, "knowledge_claims_live");
        projectionSchemaVersion = textOr(projectionSchemaVersion, "knowledge-claim-vector-v1");
        textComposerVersion = textOr(textComposerVersion, "knowledge-claim-text-v1");
        databasePath = textOr(databasePath, "data/multi-source-knowledge.db");
        candidateLimit = bounded(candidateLimit, 1, 1000, 200);
        overFetchFactor = bounded(overFetchFactor, 1, 10, 3);
        batchSize = bounded(batchSize, 1, 64, 32);
        representativeEvidenceLimit = bounded(representativeEvidenceLimit, 0, 10, 3);
        retainPhysicalCollections = bounded(retainPhysicalCollections, 1, 10, 2);
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

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int bounded(int value, int min, int max, int fallback) {
        return value < min || value > max ? fallback : value;
    }
}
