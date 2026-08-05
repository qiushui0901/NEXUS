package com.example.requirementrag.evidence;

/** 引用质量总体状态：全部可验证、需人工复核或证据不足。 */
public enum CitationQualityStatus {
    VERIFIED,
    REVIEW_REQUIRED,
    INSUFFICIENT_EVIDENCE
}
