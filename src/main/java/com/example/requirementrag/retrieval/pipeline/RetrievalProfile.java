package com.example.requirementrag.retrieval.pipeline;

/** 检索画像：不同消费方共享检索编排，同时声明各自需要的证据类型。 */
public enum RetrievalProfile {
    /** 开发计划：需求证据 + 代码证据 + 版本正文。 */
    DEVELOPMENT_PLAN(true, true),
    /** 需求评审：仅需求证据。 */
    REQUIREMENT_REVIEW(true, false),
    /** 知识库构建：需求证据 + 代码证据。 */
    WIKI_BUILD(true, true);

    private final boolean requirementEvidence;
    private final boolean codeEvidence;

    RetrievalProfile(boolean requirementEvidence, boolean codeEvidence) {
        this.requirementEvidence = requirementEvidence;
        this.codeEvidence = codeEvidence;
    }

    public boolean usesRequirementEvidence() {
        return requirementEvidence;
    }

    public boolean usesCodeEvidence() {
        return codeEvidence;
    }
}
