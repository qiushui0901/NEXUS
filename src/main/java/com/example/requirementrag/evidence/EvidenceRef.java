package com.example.requirementrag.evidence;

/** A safe, bounded reference to one item in the current retrieval result. */
public record EvidenceRef(
        String evidenceId,
        EvidenceType type,
        String projectId,
        String version,
        String title,
        String source,
        String location,
        String excerpt,
        String commitSha,
        Integer startLine,
        Integer endLine,
        String chunkId
) {
}
