package com.example.requirementrag.code;

import com.example.requirementrag.model.CodeChunk;

import java.util.List;

/** AST extraction result for one source file. */
public record ParsedCodeFile(List<CodeChunk> chunks, List<CodeSymbol> symbols,
                             List<CodeCall> calls, List<CodeScanDiagnostic> diagnostics) {
}
