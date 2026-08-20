package com.example.requirementrag.retrieval.pipeline;

import com.example.requirementrag.cache.BoundedTtlCache;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.RagOutcome;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 按检索范围隔离的结果缓存：缓存键包含 query、项目、文档版本、profile、limit
 * 与检索配置指纹，配置变更后旧缓存自动失效。
 */
@Component
public class RetrievalResultCache {
    private final BoundedTtlCache<Key, RagOutcome<RetrievalBundle>> cache;
    private final String configurationFingerprint;
    private final ObjectProvider<com.example.requirementrag.project.BusinessProjectCatalogService> catalogServiceProvider;

    public RetrievalResultCache(RagProperties properties) {
        this(properties, null);
    }

    @Autowired
    public RetrievalResultCache(RagProperties properties,
                                @Lazy ObjectProvider<com.example.requirementrag.project.BusinessProjectCatalogService> catalogServiceProvider) {
        RagProperties.Retrieval retrieval = properties.retrieval();
        RagProperties.Document document = retrieval != null ? retrieval.document() : null;
        long ttlSeconds = document != null ? document.resolvedResultCacheTtlSeconds() : retrieval.resolvedResultCacheTtlSeconds();
        int maxEntries = document != null ? document.resolvedResultCacheMaxEntries() : retrieval.resolvedResultCacheMaxEntries();
        this.cache = new BoundedTtlCache<>(
                Duration.ofSeconds(ttlSeconds),
                maxEntries);
        this.configurationFingerprint = retrieval.fingerprint();
        this.catalogServiceProvider = catalogServiceProvider;
    }

    RetrievalResultCache(Duration ttl, int maxEntries, String configurationFingerprint) {
        this.cache = new BoundedTtlCache<>(ttl, maxEntries);
        this.configurationFingerprint = configurationFingerprint;
        this.catalogServiceProvider = null;
    }

    /** 按检索范围取缓存结果。 */
    public Optional<RagOutcome<RetrievalBundle>> get(RetrievalRequest request, String projectId,
                                                      String documentId, String version, int limit) {
        return cache.get(key(request, projectId, documentId, version, limit));
    }

    /** 按检索范围写入缓存结果。 */
    public void put(RetrievalRequest request, String projectId, String documentId, String version, int limit,
                    RagOutcome<RetrievalBundle> outcome) {
        cache.put(key(request, projectId, documentId, version, limit), outcome);
    }

    /** 失效指定项目/文档/版本的全部缓存条目（需求内容替换后调用，防止旧结果残留）。 */
    public void invalidate(String projectId, String documentId, String version) {
        cache.invalidateWhere(key -> key.projectId().equals(projectId)
                && key.documentId().equals(documentId) && key.version().equals(version));
    }

    /** 失效全部项目中指定文档/版本的全部缓存条目（collection 被多项目共用、无法唯一归属时使用）。 */
    public void invalidateAll(String documentId, String version) {
        cache.invalidateWhere(key -> key.documentId().equals(documentId) && key.version().equals(version));
    }

    /** 仓库归属或公共库引用变化时失效该业务项目全部默认/显式仓库范围缓存。 */
    public void invalidateProject(String projectId) {
        if (projectId == null || projectId.isBlank()) return;
        cache.invalidateWhere(key -> key.projectId().equals(projectId));
    }

    /** 构建缓存键：query 去除首尾空白，并纳入配置指纹、仓库范围与随机种子，保证配置/实验种子/仓库归属变更即失效。 */
    private Key key(RetrievalRequest request, String projectId, String documentId, String version, int limit) {
        String catalogVersion = resolveCatalogVersion(projectId);
        return new Key(request.query().strip(), projectId, documentId, version, request.profile(), limit,
                request.includeVersionCorpus(), request.randomSeed(), request.repositoryIds(),
                configurationFingerprint, catalogVersion);
    }

    private String resolveCatalogVersion(String projectId) {
        if (catalogServiceProvider == null) return "0";
        com.example.requirementrag.project.BusinessProjectCatalogService service = catalogServiceProvider.getIfAvailable();
        if (service == null || projectId == null || projectId.isBlank()) return "0";
        try {
            return service.catalogVersion(projectId);
        } catch (Exception ignored) {
            return "0";
        }
    }

    private record Key(String query, String projectId, String documentId, String version,
                       RetrievalProfile profile, int limit, boolean includeVersionCorpus,
                       Long randomSeed, java.util.List<String> repositoryIds,
                       String configurationFingerprint, String catalogVersion) {
    }
}
