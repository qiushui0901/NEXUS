package com.example.requirementrag.code;

import com.example.requirementrag.model.CodeChunk;

import java.util.List;

/** 单个源码文件的 AST 提取结果：代码 chunk、符号、调用点与扫描诊断。 */
public record ParsedCodeFile(List<CodeChunk> chunks, List<CodeSymbol> symbols,
                             List<CodeCall> calls, List<CodeScanDiagnostic> diagnostics) {
}
