package com.example.requirementrag.model;

/**
 * 向量库分块记录：保存父子块文本（父块为上下文、子块为检索单元）、内容哈希、块内排序信息，
 * 以及来源/权威/状态/Evidence/factKey 等发布目录元数据。
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
        String sourceType,
        String documentVersionId,
        String authority,
        String status,
        String evidenceId,
        String factKey
) {
    /** 兼容旧构造器：未提供结构化元数据时填充空值，来源类型默认 REQUIREMENT。 */
    public ChunkRecord(String id, String documentId, String version, String filename,
                       String parentId, String parentText, String childText,
                       String contentHash, int parentOrder, int childOrder,
                       String sectionPath, String heading, String requirementId,
                       String module, String acceptanceCriteria) {
        this(id, documentId, version, filename, parentId, parentText, childText,
                contentHash, parentOrder, childOrder, sectionPath, heading, requirementId,
                module, acceptanceCriteria, "REQUIREMENT", "", "", "", "", "");
    }

    /** 兼容旧构造器：提供来源类型但未提供 Phase D 发布目录字段。 */
    public ChunkRecord(String id, String documentId, String version, String filename,
                       String parentId, String parentText, String childText,
                       String contentHash, int parentOrder, int childOrder,
                       String sectionPath, String heading, String requirementId,
                       String module, String acceptanceCriteria, String sourceType) {
        this(id, documentId, version, filename, parentId, parentText, childText,
                contentHash, parentOrder, childOrder, sectionPath, heading, requirementId,
                module, acceptanceCriteria, sourceType, "", "", "", "", "");
    }

    /** 兼容旧构造器：未提供结构化元数据时填充空值，来源类型默认 REQUIREMENT。 */
    public ChunkRecord(String id, String documentId, String version, String filename,
                       String parentId, String parentText, String childText,
                       String contentHash, int parentOrder, int childOrder) {
        this(id, documentId, version, filename, parentId, parentText, childText,
                contentHash, parentOrder, childOrder, "", "", "", "", "", "REQUIREMENT",
                "", "", "", "", "");
    }
}