package com.example.requirementrag.model;

/** 文档导入响应，返回文档 ID、版本与写入分块数。 */
public record IngestResponse(String documentId, String version, int chunks) {
}
