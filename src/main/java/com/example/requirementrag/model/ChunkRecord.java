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
        int childOrder,
        String sectionPath,
        String heading,
        String requirementId,
        String module,
        String acceptanceCriteria,
        String sourceType
) {
    /** 兼容旧构造器：未提供结构化元数据时填充空值，来源类型默认 REQUIREMENT。 */
    public ChunkRecord(String id, String documentId, String version, String filename,
                       String parentId, String parentText, String childText,
                       String contentHash, int parentOrder, int childOrder,
                       String sectionPath, String heading, String requirementId,
                       String module, String acceptanceCriteria) {
        this(id, documentId, version, filename, parentId, parentText, childText,
                contentHash, parentOrder, childOrder, sectionPath, heading, requirementId,
                module, acceptanceCriteria, "REQUIREMENT");
    }

    /** 兼容旧构造器：未提供结构化元数据时填充空值，来源类型默认 REQUIREMENT。 */
    public ChunkRecord(String id, String documentId, String version, String filename,
                       String parentId, String parentText, String childText,
                       String contentHash, int parentOrder, int childOrder) {
        this(id, documentId, version, filename, parentId, parentText, childText,
                contentHash, parentOrder, childOrder, "", "", "", "", "");
    }
}
