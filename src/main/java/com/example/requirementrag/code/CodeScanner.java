package com.example.requirementrag.code;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.CodeChunk;

import java.io.IOException;
import java.util.List;

/** 语言无关的仓库扫描器契约：全量扫描、按文件增量扫描与语言支持判断。 */
public interface CodeScanner {
    /** 全量扫描配置中的仓库，返回可写入向量库与符号图谱的扫描结果。 */
    ScanResult scan(RagProperties.Code config) throws IOException;

    /** 扫描指定 commit 下的部分文件（增量索引用），结构与全量扫描一致。 */
    ScanResult scanFiles(RagProperties.Code config, String commitSha, List<String> paths) throws IOException;

    /** 判断该扫描器是否支持指定路径对应的语言。 */
    boolean supports(String path);

    /** 扫描结果：chunk、符号、调用点与诊断；构造时对各列表做防御性拷贝。 */
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
