package com.example.requirementrag.retrieval.pipeline;

import com.example.requirementrag.cache.BoundedTtlCache;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.RagOutcome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** Scope-safe cache for stable unified retrieval outcomes. */
@Component
public class RetrievalResultCache {
    private final BoundedTtlCache<Key, RagOutcome<RetrievalBundle>> cache;
    private final String configurationFingerprint;

    @Autowired
    public RetrievalResultCache(RagProperties properties) {
        RagProperties.Retrieval retrieval = properties.retrieval();
        this.cache = new BoundedTtlCache<>(
                Duration.ofSeconds(retrieval.resolvedResultCacheTtlSeconds()),
                retrieval.resolvedResultCacheMaxEntries());
        this.configurationFingerprint = retrieval.fingerprint();
    }

    RetrievalResultCache(Duration ttl, int maxEntries, String configurationFingerprint) {
        this.cache = new BoundedTtlCache<>(ttl, maxEntries);
        this.configurationFingerprint = configurationFingerprint;
    }

    public Optional<RagOutcome<RetrievalBundle>> get(RetrievalRequest request, String projectId,
                                                      String documentId, String version, int limit) {
        return cache.get(key(request, projectId, documentId, version, limit));
    }

    public void put(RetrievalRequest request, String projectId, String documentId, String version, int limit,
                    RagOutcome<RetrievalBundle> outcome) {
        cache.put(key(request, projectId, documentId, version, limit), outcome);
    }

    private Key key(RetrievalRequest request, String projectId, String documentId, String version, int limit) {
        return new Key(request.query().strip(), projectId, documentId, version, request.profile(), limit,
                request.includeVersionCorpus(), configurationFingerprint);
    }

    private record Key(String query, String projectId, String documentId, String version,
                       RetrievalProfile profile, int limit, boolean includeVersionCorpus,
                       String configurationFingerprint) {
    }
}
