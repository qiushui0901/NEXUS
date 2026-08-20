package com.example.requirementrag.model;

import java.util.List;

/** 文档导入响应，返回文档 ID、版本、写入分块数与解析质量诊断（如被截断的来源）。 */
public record IngestResponse(String documentId, String version, int chunks,
                             List<String> truncatedSources) {
    /** 兼容旧构造器：无解析诊断。 */
    public IngestResponse(String documentId, String version, int chunks) {
        this(documentId, version, chunks, List.of());
    }

    public IngestResponse {
        truncatedSources = truncatedSources == null ? List.of() : List.copyOf(truncatedSources);
    }
}
