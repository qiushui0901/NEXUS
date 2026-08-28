package com.example.requirementrag.knowledge.multisource.entity;

/** 实体提取校验/提取失败异常：code 为稳定错误码，供上层降级与告警。 */
public class EntityExtractionException extends RuntimeException {

    private final String code;

    public EntityExtractionException(String code, String message) {
        super(message);
        this.code = code == null || code.isBlank() ? "ENTITY_EXTRACTION_FAILED" : code;
    }

    public String code() {
        return code;
    }
}