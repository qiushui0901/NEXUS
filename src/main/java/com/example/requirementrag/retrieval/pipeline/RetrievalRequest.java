package com.example.requirementrag.retrieval.pipeline;

/** Input contract for the shared requirement/code evidence retrieval pipeline. */
public record RetrievalRequest(
        String query,
        RetrievalProfile profile,
        String projectId,
        String documentId,
        String version,
        Integer limit,
        boolean includeVersionCorpus
) {
    public RetrievalRequest(String query, RetrievalProfile profile, String projectId, String documentId,
                            String version, Integer limit) {
        this(query, profile, projectId, documentId, version, limit, false);
    }

    public RetrievalRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query required");
        }
        profile = profile == null ? RetrievalProfile.DEVELOPMENT_PLAN : profile;
    }
}
