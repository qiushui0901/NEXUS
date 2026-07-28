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
        List<ChunkRecord> requirementCorpus,
        List<CodeChunk> codeEvidence
) {
    public RetrievalBundle(String query, RetrievalProfile profile, String resolvedProjectId, String documentId,
                           String version, List<ChunkRecord> requirementEvidence, List<CodeChunk> codeEvidence) {
        this(query, profile, resolvedProjectId, documentId, version, requirementEvidence, List.of(), codeEvidence);
    }

    public RetrievalBundle {
        requirementEvidence = requirementEvidence == null ? List.of() : List.copyOf(requirementEvidence);
        requirementCorpus = requirementCorpus == null ? List.of() : List.copyOf(requirementCorpus);
        codeEvidence = codeEvidence == null ? List.of() : List.copyOf(codeEvidence);
    }
}
