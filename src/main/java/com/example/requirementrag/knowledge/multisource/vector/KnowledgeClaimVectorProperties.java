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

    /** 将 scope 分量规整为 Qdrant 集合名安全 token（小写字母数字与连字符，截断防超长）。 */
    private static String scopeToken(String value) {
        String normalized = value == null ? "unknown" : value.trim().toLowerCase()
                .replaceAll("[^a-z0-9._-]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (normalized.isEmpty()) {
            normalized = "unknown";
        }
        return normalized.length() > 32 ? normalized.substring(0, 32) : normalized;
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int bounded(int value, int min, int max, int fallback) {
        return value < min || value > max ? fallback : value;
    }
}
