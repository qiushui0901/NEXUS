package com.example.requirementrag.model;

/**
 * 向量库分块记录：保存父子块文本（父块为上下文、子块为检索单元）、内容哈希与块内排序信息，
 * 用于去重、溯源与命中结果还原。
 */
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
