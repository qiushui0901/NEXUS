package com.example.requirementrag.evidence;

/** 指向当前检索结果中某一项证据的安全有界引用。 */
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
