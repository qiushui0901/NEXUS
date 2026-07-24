package com.example.requirementrag.code;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.code.GitDiffService.GitDiffResult;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.IncrementalCodeIndexResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * 基于 Git diff 的增量代码索引服务。
 */
@Service
public class IncrementalCodeIndexService {

    private static final Logger log = LoggerFactory.getLogger(IncrementalCodeIndexService.class);
    private static final String ZERO_SHA = "0000000000000000000000000000000000000000";

    private final ProjectRegistry projectRegistry;
    private final JavaCodeScanner scanner;
    private final CodeQdrantStore store;
    private final GitDiffService gitDiffService;

    public IncrementalCodeIndexService(ProjectRegistry projectRegistry, JavaCodeScanner scanner,
                                       CodeQdrantStore store, GitDiffService gitDiffService) {
        this.projectRegistry = projectRegistry;
        this.scanner = scanner;
        this.store = store;
        this.gitDiffService = gitDiffService;
    }

    /**
     * 兼容 webhook 的后台入口：失败时记录日志，不让 webhook 线程直接退出。
     */
    public void index(String projectId, String oldSha, String newSha) {
        try {
            indexWithResult(projectId, oldSha, newSha);
        }
        catch (Exception exception) {
            log.error("增量索引失败 {}: {}", projectId, exception.getMessage(), exception);
        }
    }

    /**
     * 按 Git commit 范围执行增量索引，并返回可审计的数量摘要。
     * 只写入配置的版本化代码 collection，不写入本地向量库文件。
     */
    public IncrementalCodeIndexResponse indexWithResult(String projectId, String oldSha, String newSha)
            throws IOException, InterruptedException {
        if (isZeroSha(oldSha) || isZeroSha(newSha)) {
            log.info("跳过增量索引 {}: 无效 commit 范围 {}..{}", projectId, oldSha, newSha);
            return new IncrementalCodeIndexResponse(projectId, oldSha, newSha, 0, 0, 0);
        }
        RagProperties.ProjectConfig project = projectRegistry.require(projectId);
        RagProperties.Code codeConfig = project.toCodeConfig();
        GitDiffResult diff = gitDiffService.diff(projectId, oldSha, newSha);
        List<String> changedFiles = diff.changedPaths();
        List<String> javaFiles = changedFiles.stream()
                .map(this::normalizePath)
                .filter(path -> path.endsWith(".java"))
                .distinct()
                .toList();
        if (javaFiles.isEmpty()) {
            log.info("增量索引 {}: 无变更 Java 文件", projectId);
            return new IncrementalCodeIndexResponse(projectId, oldSha, newSha, changedFiles.size(), 0, 0);
        }

        String collection = projectRegistry.resolveCodeCollection(projectId);
        for (String filePath : javaFiles) {
            store.deleteFileChunks(collection, projectId, filePath);
        }

        List<CodeChunk> chunks = scanner.scanFiles(codeConfig, newSha, javaFiles);
        store.upsertChunks(collection, chunks);
        log.info("增量索引完成 {}: {} 个 Java 文件, {} 个 chunk", projectId, javaFiles.size(), chunks.size());
        return new IncrementalCodeIndexResponse(projectId, oldSha, newSha,
                changedFiles.size(), javaFiles.size(), chunks.size());
    }

    private String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    private boolean isZeroSha(String sha) {
        return sha == null || sha.isBlank() || ZERO_SHA.equals(sha);
    }
}
