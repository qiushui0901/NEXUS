package com.example.requirementrag.code;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.code.GitDiffService.GitDiffResult;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.IncrementalCodeIndexResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

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
    private final CodeScanner scanner;
    private final CodeQdrantStore store;
    private final GitDiffService gitDiffService;
    private final SQLiteSymbolGraphStore graphStore;

    @Autowired
    public IncrementalCodeIndexService(ProjectRegistry projectRegistry, CodeScanner scanner,
                                       CodeQdrantStore store, GitDiffService gitDiffService,
                                       SQLiteSymbolGraphStore graphStore) {
        this.projectRegistry = projectRegistry;
        this.scanner = scanner;
        this.store = store;
        this.gitDiffService = gitDiffService;
        this.graphStore = graphStore;
    }

    /** Compatibility constructor for pre-0.7 unit callers. */
    IncrementalCodeIndexService(ProjectRegistry projectRegistry, JavaCodeScanner scanner,
                                CodeQdrantStore store, GitDiffService gitDiffService) {
        this(projectRegistry, CodeKnowledgeService.legacy(scanner), store, gitDiffService, null);
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
        List<String> sourceFiles = changedFiles.stream()
                .map(this::normalizePath)
                .filter(scanner::supports)
                .distinct()
                .toList();
        if (sourceFiles.isEmpty()) {
            log.info("增量索引 {}: 无受支持的源码文件变更", projectId);
            return new IncrementalCodeIndexResponse(projectId, oldSha, newSha, changedFiles.size(), 0, 0);
        }

        String liveCollection = projectRegistry.resolveCodeCollection(projectId) + "-live";
        CodeScanner.ScanResult changed = scanner.scanFiles(codeConfig, newSha, sourceFiles);
        List<CodeChunk> chunks = changed.chunks();
        store.upsertChunks(liveCollection, chunks);
        for (String filePath : sourceFiles) {
            store.deleteFileChunks(liveCollection, projectId, filePath);
        }
        if (graphStore != null) {
            try {
                CodeScanner.ScanResult snapshot = scanner.scan(codeConfig);
                if (newSha.equals(snapshot.commitSha())) {
                    graphStore.replaceSnapshot(snapshot);
                }
                else {
                    log.warn("跳过静态图谱快照 {}: 工作区 HEAD {} 与目标 commit {} 不一致",
                            projectId, snapshot.commitSha(), newSha);
                }
            } catch (IllegalArgumentException exception) {
                log.warn("跳过静态图谱快照 {}: {}", projectId, exception.getMessage());
            }
        }
        log.info("增量索引完成 {}: {} 个源码文件, {} 个 chunk（写入 live alias {}）",
                projectId, sourceFiles.size(), chunks.size(), liveCollection);
        return new IncrementalCodeIndexResponse(projectId, oldSha, newSha,
                changedFiles.size(), sourceFiles.size(), chunks.size());
    }

    private String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    private boolean isZeroSha(String sha) {
        return sha == null || sha.isBlank() || ZERO_SHA.equals(sha);
    }
}
