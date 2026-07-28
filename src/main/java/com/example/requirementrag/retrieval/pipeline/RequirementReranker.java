package com.example.requirementrag.retrieval.pipeline;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.RagOutcome;

import java.util.List;

/** Profile-independent requirement evidence reranking boundary. */
public interface RequirementReranker {
    RagOutcome<List<ChunkRecord>> rerank(String query, String documentId, String version,
                                         List<ChunkRecord> candidates, int limit);

    static RequirementReranker passthrough() {
        return (query, documentId, version, candidates, limit) -> {
            List<ChunkRecord> values = candidates == null ? List.of()
                    : candidates.stream().limit(limit).toList();
            return RagOutcome.of(values.isEmpty()
                            ? com.example.requirementrag.model.RagOutcomeStatus.NO_RESULTS
                            : com.example.requirementrag.model.RagOutcomeStatus.SUCCESS,
                    values, "retrieval.rerank", 0, values.size());
        };
    }
}
