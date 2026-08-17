package com.example.requirementrag.evaluation;

import java.util.List;

/** Versioned, stable-label retrieval evaluation case loaded from JSONL. */
public record RetrievalEvaluationCase(
        String id,
        String query,
        RetrievalProfile profile,
        String projectId,
        String documentId,
        String version,
        ExpectedOutcome expectedOutcome,
        List<GoldDocument> goldDocuments,
        List<GoldCode> goldCode,
        List<String> tags,
        String notes,
        Integer schemaVersion,
        QueryType queryType,
        String sourceCommit,
        Review review
) {
    /** Backward-compatible constructor for the v1 evaluation contract. */
    public RetrievalEvaluationCase(
            String id,
            String query,
            RetrievalProfile profile,
            String projectId,
            String documentId,
            String version,
            ExpectedOutcome expectedOutcome,
            List<GoldDocument> goldDocuments,
            List<GoldCode> goldCode,
            List<String> tags,
            String notes
    ) {
        this(id, query, profile, projectId, documentId, version, expectedOutcome,
                goldDocuments, goldCode, tags, notes, null, null, null, null);
    }

    public RetrievalEvaluationCase {
        goldDocuments = immutable(goldDocuments);
        goldCode = immutable(goldCode);
        tags = immutable(tags);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public enum RetrievalProfile {
        DEVELOPMENT_PLAN,
        REQUIREMENT_REVIEW,
        WIKI_BUILD
    }

    public enum ExpectedOutcome {
        HIT,
        NO_RESULTS
    }

    public enum QueryType {
        BUSINESS_SEMANTIC,
        CROSS_DOCUMENT,
        MULTI_HOP,
        HISTORICAL_VERSION,
        NO_ANSWER,
        REQUIREMENT_CODE_JOINT
    }

    public enum ReviewStatus {
        APPROVED,
        REJECTED
    }

    public record Review(ReviewStatus status, String reviewer, String reviewedAt) {
    }

    public record GoldDocument(
            String filename,
            Integer parentOrder,
            Integer childOrder,
            List<String> mustContain,
            String evidenceId
    ) {
        public GoldDocument(String filename, Integer parentOrder, List<String> mustContain) {
            this(filename, parentOrder, null, mustContain, null);
        }

        public GoldDocument(String filename, Integer parentOrder, Integer childOrder, List<String> mustContain) {
            this(filename, parentOrder, childOrder, mustContain, null);
        }

        public GoldDocument {
            mustContain = mustContain == null ? List.of() : List.copyOf(mustContain);
        }
    }

    public record GoldCode(String projectId, String filePath, String symbolName, String evidenceId) {
        public GoldCode(String projectId, String filePath, String symbolName) {
            this(projectId, filePath, symbolName, null);
        }
    }
}
