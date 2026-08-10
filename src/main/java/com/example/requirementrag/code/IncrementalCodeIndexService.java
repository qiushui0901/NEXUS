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
    private final CodeIndexLockService indexLockService;

    @Autowired
    public IncrementalCodeIndexService(ProjectRegistry projectRegistry, CodeScanner scanner,
                                       CodeQdrantStore store, GitDiffService gitDiffService,
                                       SQLiteSymbolGraphStore graphStore,
                                       CodeIndexLockService indexLockService) {
        this.projectRegistry = projectRegistry;
        this.scanner = scanner;
        this.store = store;
        this.gitDiffService = gitDiffService;
        this.graphStore = graphStore;
        this.indexLockService = indexLockService;
    }

    /** Compatibility constructor for pre-0.7 unit callers. */
    IncrementalCodeIndexService(ProjectRegistry projectRegistry, JavaCodeScanner scanner,
                                CodeQdrantStore store, GitDiffService gitDiffService) {
        this(projectRegistry, CodeKnowledgeService.legacy(scanner), store, gitDiffService, null,
                new CodeIndexLockService());
    }

    /** 兼容旧测试调用方：不携带共享锁。 */
    IncrementalCodeIndexService(ProjectRegistry projectRegistry, CodeScanner scanner,
                                CodeQdrantStore store, GitDiffService gitDiffService) {
        this(projectRegistry, scanner, store, gitDiffService, null, new CodeIndexLockService());
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

        return indexLockService.execute(projectId, () -> {
            try {
                applyIncremental(projectId, codeConfig, newSha, sourceFiles);
                return buildResponse(projectId, oldSha, newSha, changedFiles, sourceFiles);
            } catch (IOException | InterruptedException exception) {
                throw new IncrementalExecutionException(exception);
            }
        });
    }

    /**
     * 在 live alias 上执行文件级安全替换：先快照旧 chunk ID，再写入新 chunk（新 ID），
     * 最后只按旧 ID 删除——新 chunk 永不被删除 API 波及；
     * 任一步失败时旧数据保留（最多新旧并存，下次索引收敛）。
     */
    private void applyIncremental(String projectId, RagProperties.Code codeConfig, String newSha,
                                  List<String> sourceFiles) throws IOException, InterruptedException {
        String liveCollection = projectRegistry.resolveCodeCollection(projectId) + "-live";
        java.util.Map<String, List<String>> oldIdsByFile = new java.util.LinkedHashMap<>();
        for (String filePath : sourceFiles) {
            oldIdsByFile.put(filePath, store.scrollChunkIds(liveCollection, projectId, filePath, 10_000));
        }
        CodeScanner.ScanResult changed = scanner.scanFiles(codeConfig, newSha, sourceFiles);
        List<CodeChunk> chunks = changed.chunks();
        store.upsertChunks(liveCollection, chunks);
        int deleted = 0;
        for (String filePath : sourceFiles) {
            List<String> oldIds = oldIdsByFile.getOrDefault(filePath, List.of());
            store.deleteChunks(liveCollection, oldIds);
            deleted += oldIds.size();
        }
        log.info("增量索引完成 {}: {} 个源码文件, {} 个新 chunk, 删除 {} 个旧 chunk（live alias {}）",
                projectId, sourceFiles.size(), chunks.size(), deleted, liveCollection);
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
    }

    private IncrementalCodeIndexResponse buildResponse(String projectId, String oldSha, String newSha,
                                                       List<String> changedFiles, List<String> sourceFiles) {
        return new IncrementalCodeIndexResponse(projectId, oldSha, newSha,
                changedFiles.size(), sourceFiles.size(), sourceFiles.size());
    }

    /** 包装受检异常以适配锁服务函数式接口。 */
    private static final class IncrementalExecutionException extends RuntimeException {
        IncrementalExecutionException(Exception cause) {
            super(cause);
        }
    }

    private String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    private boolean isZeroSha(String sha) {
        return sha == null || sha.isBlank() || ZERO_SHA.equals(sha);
    }
}
