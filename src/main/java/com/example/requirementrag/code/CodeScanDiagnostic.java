package com.example.requirementrag.code;

/** 扫描器对某语言或某文件可见的降级/诊断信息，如解析器不可用、文件被跳过、解析失败等。 */
public record CodeScanDiagnostic(String language, String filePath, String code, String message) {
}
