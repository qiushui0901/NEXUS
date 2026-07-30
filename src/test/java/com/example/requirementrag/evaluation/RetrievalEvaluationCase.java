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
        String notes
) {
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

    public record GoldDocument(String filename, Integer parentOrder, List<String> mustContain) {
        public GoldDocument {
            mustContain = mustContain == null ? List.of() : List.copyOf(mustContain);
        }
    }

    public record GoldCode(String projectId, String filePath, String symbolName) {
    }
}
