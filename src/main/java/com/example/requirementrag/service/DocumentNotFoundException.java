package com.example.requirementrag.service;

/**
 * 指定文档与版本在向量库中不存在时抛出的异常。
 */
public class DocumentNotFoundException extends RuntimeException {

    /**
     * 构造异常，携带文档 ID 与版本信息。
     */
    public DocumentNotFoundException(String documentId, String version) {
        super("未找到文档，documentId=" + documentId + ", version=" + version);
    }
}
