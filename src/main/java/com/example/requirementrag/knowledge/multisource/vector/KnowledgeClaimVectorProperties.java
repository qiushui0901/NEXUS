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

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int bounded(int value, int min, int max, int fallback) {
        return value < min || value > max ? fallback : value;
    }
}
