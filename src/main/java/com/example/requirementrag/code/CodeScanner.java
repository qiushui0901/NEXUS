package com.example.requirementrag.code;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.CodeChunk;

import java.io.IOException;
import java.util.List;

/** Language-neutral repository scanner contract. */
public interface CodeScanner {
    ScanResult scan(RagProperties.Code config) throws IOException;

    ScanResult scanFiles(RagProperties.Code config, String commitSha, List<String> paths) throws IOException;

    boolean supports(String path);

    record ScanResult(String projectId, String commitSha, int files, List<CodeChunk> chunks,
                      List<CodeSymbol> symbols, List<CodeCall> calls,
                      List<CodeScanDiagnostic> diagnostics) {
        public ScanResult {
            chunks = List.copyOf(chunks);
            symbols = List.copyOf(symbols);
            calls = List.copyOf(calls);
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
