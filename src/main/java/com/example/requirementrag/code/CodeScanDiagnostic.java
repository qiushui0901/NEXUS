package com.example.requirementrag.code;

/** Visible per-language or per-file scanner degradation. */
public record CodeScanDiagnostic(String language, String filePath, String code, String message) {
}
