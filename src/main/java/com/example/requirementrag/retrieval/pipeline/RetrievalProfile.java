package com.example.requirementrag.retrieval.pipeline;

/** Different consumers can share retrieval orchestration while declaring their evidence needs. */
public enum RetrievalProfile {
    DEVELOPMENT_PLAN(true, true),
    REQUIREMENT_REVIEW(true, false),
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
