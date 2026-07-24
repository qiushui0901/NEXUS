package com.example.requirementrag.retrieval.pipeline;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;

import java.util.List;

/** Evidence returned by the shared retrieval pipeline. */
public record RetrievalBundle(
        String query,
        RetrievalProfile profile,
        String resolvedProjectId,
        String documentId,
        String version,
        List<ChunkRecord> requirementEvidence,
        List<CodeChunk> codeEvidence
) {
    public RetrievalBundle {
        requirementEvidence = requirementEvidence == null ? List.of() : List.copyOf(requirementEvidence);
        codeEvidence = codeEvidence == null ? List.of() : List.copyOf(codeEvidence);
    }
}
