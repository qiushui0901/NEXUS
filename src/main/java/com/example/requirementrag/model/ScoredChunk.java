package com.example.requirementrag.model;

/** 带 Qdrant 原生相关性分数的分块记录。 */
public record ScoredChunk(ChunkRecord record, double score) {
}
