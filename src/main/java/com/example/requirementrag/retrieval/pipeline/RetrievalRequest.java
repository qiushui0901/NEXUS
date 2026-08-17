package com.example.requirementrag.retrieval.pipeline;

/** 共享需求/代码证据检索管线的输入契约。 */
public record RetrievalRequest(
        String query,
        RetrievalProfile profile,
        String projectId,
        String documentId,
        String version,
        Integer limit,
        boolean includeVersionCorpus,
        Long randomSeed
) {
    public RetrievalRequest(String query, RetrievalProfile profile, String projectId, String documentId,
                            String version, Integer limit) {
        this(query, profile, projectId, documentId, version, limit, false, null);
    }

    public RetrievalRequest(String query, RetrievalProfile profile, String projectId, String documentId,
                            String version, Integer limit, boolean includeVersionCorpus) {
        this(query, profile, projectId, documentId, version, limit, includeVersionCorpus, null);
    }

    public RetrievalRequest(String query, RetrievalProfile profile, String projectId, String documentId,
                            String version, Integer limit, Long randomSeed) {
        this(query, profile, projectId, documentId, version, limit, false, randomSeed);
    }

    /** 校验 query 必填；profile 为空时默认使用 DEVELOPMENT_PLAN。 */
    public RetrievalRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query required");
        }
        profile = profile == null ? RetrievalProfile.DEVELOPMENT_PLAN : profile;
    }
}
