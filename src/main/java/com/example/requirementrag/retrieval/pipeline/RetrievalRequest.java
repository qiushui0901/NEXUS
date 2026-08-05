package com.example.requirementrag.retrieval.pipeline;

/** 共享需求/代码证据检索管线的输入契约。 */
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

    /** 校验 query 必填；profile 为空时默认使用 DEVELOPMENT_PLAN。 */
    public RetrievalRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query required");
        }
        profile = profile == null ? RetrievalProfile.DEVELOPMENT_PLAN : profile;
    }
}
