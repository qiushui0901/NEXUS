package com.example.requirementrag.retrieval.pipeline;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;

import java.util.List;

/**
 * 共享检索管线返回的证据结果：查询信息、解析后的项目 ID、需求证据、需求正文与代码证据。
 * 各证据列表在构造时做防御性拷贝，保证不可变。
 */
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
    /** 便捷构造：未请求正文时以空列表填充 requirementCorpus。 */
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
