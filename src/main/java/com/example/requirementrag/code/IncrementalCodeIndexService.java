package com.example.requirementrag.code;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.CodeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * 基于 Git diff 的增量代码索引服务。
 */
@Service
public class IncrementalCodeIndexService {

    private static final Logger log = LoggerFactory.getLogger(IncrementalCodeIndexService.class);
    private static final String ZERO_SHA = "0000000000000000000000000000000000000000";

    private final RagProperties properties;
    private final ProjectRegistry projectRegistry;
    private final JavaCodeScanner scanner;
    private final CodeQdrantStore store;

    public IncrementalCodeIndexService(RagProperties properties, ProjectRegistry projectRegistry,
                                       JavaCodeScanner scanner, CodeQdrantStore store) {
        this.properties = properties;
        this.projectRegistry = projectRegistry;
        this.scanner = scanner;
        this.store = store;
    }

    public void index(String projectId, String oldSha, String newSha) {
        try {
            if (isZeroSha(oldSha) || isZeroSha(newSha)) {
                log.info("跳过增量索引 {}: 无效 commit 范围 {}..{}", projectId, oldSha, newSha);
                return;
            }
            RagProperties.ProjectConfig project = projectRegistry.require(projectId);
            RagProperties.Code codeConfig = project.toCodeConfig();
            Path repo = Path.of(resolveRepositoryPath(project)).toAbsolutePath().normalize();
            List<String> changedFiles = gitDiffFiles(repo, oldSha, newSha);
            List<String> javaFiles = changedFiles.stream()
                    .map(this::normalizePath)
                    .filter(path -> path.endsWith(".java"))
                    .toList();
            if (javaFiles.isEmpty()) {
                log.info("增量索引 {}: 无变更 Java 文件", projectId);
                return;
            }

            String collection = projectRegistry.resolveCodeCollection(projectId);
            for (String filePath : javaFiles) {
                store.deleteFileChunks(collection, projectId, filePath);
            }

            List<CodeChunk> chunks = scanner.scanFiles(codeConfig, newSha, javaFiles);
            store.upsertChunks(collection, chunks);
            log.info("增量索引完成 {}: {} 个 Java 文件, {} 个 chunk", projectId, javaFiles.size(), chunks.size());
        }
        catch (Exception exception) {
            log.error("增量索引失败 {}: {}", projectId, exception.getMessage(), exception);
        }
    }

    private List<String> gitDiffFiles(Path repo, String oldSha, String newSha) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "diff", "--name-only", oldSha, newSha)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git diff 失败: " + output.trim());
        }
        return output.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private String resolveRepositoryPath(RagProperties.ProjectConfig project) {
        String path = project.repositoryPath();
        if (path != null && !path.isBlank()) {
            return path;
        }
        return properties.code().repositoryPath();
    }

    private String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    private boolean isZeroSha(String sha) {
        return sha == null || sha.isBlank() || ZERO_SHA.equals(sha);
    }
}
