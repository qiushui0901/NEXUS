package com.example.requirementrag.model;

/** 向量库分块记录，含父子块文本、哈希与排序信息。 */
public record ChunkRecord(
        String id,
        String documentId,
        String version,
        String filename,
        String parentId,
        String parentText,
        String childText,
        String contentHash,
        int parentOrder,
        int childOrder
) {
}
