package com.example.requirementrag.model;

/** Selects either symbol impact or commit-range impact. */
public record ImpactAnalysisRequest(String projectId, String symbol, String fromCommit, String toCommit,
                                    Integer depth, Integer limit) {
}
