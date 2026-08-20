package com.example.requirementrag.code;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 对已验证的 commit SHA 执行固定文件级 Git diff，输出变更统计与文件变更列表。 */
@Service
public class GitDiffService {
    private static final Logger log = LoggerFactory.getLogger(GitDiffService.class);
    private static final Pattern COMMIT = Pattern.compile("[0-9a-fA-F]{7,64}");

    /** 差异分析结果的可用性状态。 */
    public enum Availability { AVAILABLE, NOT_AVAILABLE }
    /** 文件变更类型：新增/修改/删除/重命名。 */
    public enum ChangeType { ADDED, MODIFIED, DELETED, RENAMED }

    /** 单个文件变更：类型与新旧路径（新增时旧路径为空，删除时新路径为空，重命名时两者均有）。 */
    public record GitFileChange(ChangeType type, String oldPath, String newPath) {}

    /** 一次 diff 的汇总结果：总变更数、按类型计数、Java/测试/配置文件数量与逐文件变更列表。 */
    public record GitDiffResult(
            Availability availability,
            int changedFiles,
            int added,
            int modified,
            int deleted,
            int renamed,
            int javaFiles,
            int testFiles,
            int configFiles,
            List<GitFileChange> changes
    ) {
        public GitDiffResult {
            changes = changes == null ? List.of() : List.copyOf(changes);
        }
        /** 返回不可用占位结果（如仓库未配置或不可用时）。 */
        public static GitDiffResult unavailable() {
            return new GitDiffResult(Availability.NOT_AVAILABLE, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }

        /** 返回去重后的变更路径集合（新旧路径均计入）。 */
        public List<String> changedPaths() {
            return changes.stream().flatMap(change -> java.util.stream.Stream.of(change.oldPath(), change.newPath()))
                    .filter(path -> path != null && !path.isBlank()).distinct().toList();
        }
    }

    private final RagProperties properties;
    private final ProjectRegistry projectRegistry;

    public GitDiffService(RagProperties properties, ProjectRegistry projectRegistry) {
        this.properties = properties;
        this.projectRegistry = projectRegistry;
    }

    /**
     * 计算 fromCommit 到 toCommit 的文件级变更。
     *
     * @param projectId  项目 ID
     * @param fromCommit 起始 commit SHA（7-64 位十六进制）
     * @param toCommit   目标 commit SHA
     * @return 变更统计与逐文件变更列表
     * @throws IOException          执行 git 命令失败
     * @throws InterruptedException 等待 git 进程被中断
     */
    public GitDiffResult diff(String projectId, String fromCommit, String toCommit)
            throws IOException, InterruptedException {
        commit(fromCommit, "fromCommit");
        commit(toCommit, "toCommit");
        RagProperties.ProjectConfig project = projectRegistry.require(projectId);
        String configuredPath = hasText(project.repositoryPath())
                ? project.repositoryPath() : properties.code().repositoryPath();
        return diffInRepository(projectId, configuredPath, fromCommit, toCommit);
    }

    /** 在业务项目目录解析出的仓库路径执行 commit diff，避免旧 ProjectRegistry 回退。 */
    public GitDiffResult diffInRepository(String repositoryId, String repositoryPath,
                                           String fromCommit, String toCommit)
            throws IOException, InterruptedException {
        String from = commit(fromCommit, "fromCommit");
        String to = commit(toCommit, "toCommit");
        if (!hasText(repositoryPath)) throw new IllegalStateException("项目代码仓库未配置");
        Path repository = Path.of(repositoryPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(repository.resolve(".git"))) throw new IllegalStateException("项目代码仓库不可用");

        Process process = new ProcessBuilder("git", "diff", "--name-status", "-M", from, to)
                .directory(repository.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.warn("Git diff failed for repository {} with exit code {}", repositoryId, exitCode);
            throw new IllegalStateException("Git 版本差异分析失败");
        }
        List<GitFileChange> changes = output.lines().map(this::parse).filter(java.util.Objects::nonNull).toList();
        return summarize(changes);
    }

    /**
     * 返回项目代码仓库当前 HEAD 的 commit SHA。
     *
     * @param projectId 项目 ID
     * @return 当前 HEAD 的完整 commit SHA
     * @throws IOException          执行 git 命令失败
     * @throws InterruptedException 等待 git 进程被中断
     */
    public String latestCommit(String projectId) throws IOException, InterruptedException {
        RagProperties.ProjectConfig project = projectRegistry.require(projectId);
        String configuredPath = hasText(project.repositoryPath())
                ? project.repositoryPath() : properties.code().repositoryPath();
        if (!hasText(configuredPath)) throw new IllegalStateException("项目代码仓库未配置");
        Path repository = Path.of(configuredPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(repository.resolve(".git"))) throw new IllegalStateException("项目代码仓库不可用");

        Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(repository.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int exitCode = process.waitFor();
        if (exitCode != 0 || !COMMIT.matcher(output).matches()) {
            log.warn("git rev-parse HEAD failed for project {}", projectId);
            throw new IllegalStateException("Git 仓库状态不可用");
        }
        return output;
    }

    /** 解析 git diff --name-status 输出的一行（制表符分隔），无法识别时返回 null。 */
    private GitFileChange parse(String line) {
        String[] fields = line.split("\\t");
        if (fields.length < 2) return null;
        String status = fields[0].trim();
        if (status.startsWith("R") && fields.length >= 3) {
            return new GitFileChange(ChangeType.RENAMED, normalize(fields[1]), normalize(fields[2]));
        }
        String path = normalize(fields[1]);
        return switch (status.isEmpty() ? '?' : status.charAt(0)) {
            case 'A' -> new GitFileChange(ChangeType.ADDED, null, path);
            case 'M', 'T' -> new GitFileChange(ChangeType.MODIFIED, path, path);
            case 'D' -> new GitFileChange(ChangeType.DELETED, path, null);
            default -> null;
        };
    }

    /** 按变更类型汇总统计，并额外计算 Java/测试/配置文件数量。 */
    private GitDiffResult summarize(List<GitFileChange> changes) {
        int added = count(changes, ChangeType.ADDED);
        int modified = count(changes, ChangeType.MODIFIED);
        int deleted = count(changes, ChangeType.DELETED);
        int renamed = count(changes, ChangeType.RENAMED);
        List<String> paths = changes.stream().map(change -> hasText(change.newPath()) ? change.newPath() : change.oldPath()).toList();
        int javaFiles = (int) paths.stream().filter(path -> path.endsWith(".java")).count();
        int testFiles = (int) paths.stream().filter(this::isTest).count();
        int configFiles = (int) paths.stream().filter(this::isConfig).count();
        return new GitDiffResult(Availability.AVAILABLE, changes.size(), added, modified, deleted, renamed,
                javaFiles, testFiles, configFiles, changes);
    }

    private int count(List<GitFileChange> changes, ChangeType type) {
        return (int) changes.stream().filter(change -> change.type() == type).count();
    }

    private boolean isTest(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("/test/") || lower.endsWith("test.java") || lower.endsWith("tests.java");
    }

    private boolean isConfig(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".properties")
                || lower.endsWith(".xml") || lower.endsWith(".json") || lower.endsWith(".toml");
    }

    /** 校验并规范化 commit SHA：必须为 7-64 位十六进制，非法时抛出 IllegalArgumentException。 */
    private String commit(String value, String field) {
        if (!hasText(value) || !COMMIT.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException(field + " 必须是具体的 Git commit SHA");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().replace('\\', '/');
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
