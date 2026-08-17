package com.example.requirementrag.integration.gitlab;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** 受控 Git 命令客户端：凭据只通过临时 GIT_ASKPASS 注入，不进入 URL、参数或日志。 */
@Component
@ConditionalOnProperty(name = "app.rag.gitlab.enabled", havingValue = "true")
public class GitLabGitClient {

    private static final Pattern PROJECT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{40}");
    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
    private final Path repositoryRoot;
    private final Duration timeout;
    private final Set<String> allowedHosts;
    private final boolean allowPrivateHosts;
    private final AddressResolver addressResolver;
    private final ExecutorService outputExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public GitLabGitClient(GitLabIntegrationProperties properties) {
        this(properties, InetAddress::getAllByName);
    }

    GitLabGitClient(GitLabIntegrationProperties properties, AddressResolver addressResolver) {
        this.repositoryRoot = Path.of(properties.repositoryRootPath()).toAbsolutePath().normalize();
        this.timeout = Duration.ofSeconds(properties.gitTimeoutSeconds());
        this.allowedHosts = Set.copyOf(properties.allowedHosts());
        this.allowPrivateHosts = properties.allowPrivateHosts();
        this.addressResolver = addressResolver;
        try {
            Files.createDirectories(repositoryRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建 GitLab 仓库根目录", exception);
        }
    }

    /** 计算项目受控仓库路径并阻止路径逃逸。 */
    public Path repositoryPath(String projectId) {
        validateProjectId(projectId);
        Path repository = repositoryRoot.resolve(projectId).normalize();
        if (!repository.startsWith(repositoryRoot)) {
            throw new IllegalArgumentException("项目仓库路径无效");
        }
        return repository;
    }

    /** 使用与正式同步相同的 URL、Host、分支和凭据规则执行只读远端检查。 */
    public ValidationResult validateRemote(String cloneUrl, String branch, String accessToken) {
        validateCloneUrl(cloneUrl);
        validateBranch(branch);
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("GitLab accessToken 不能为空");
        }
        String output = runGit(repositoryRoot, accessToken, "ls-remote", "--exit-code", "--heads",
                "--", cloneUrl, "refs/heads/" + branch).trim();
        String sha = output.isBlank() ? "" : output.split("\\s+", 2)[0];
        validateSha(sha);
        URI uri = URI.create(cloneUrl);
        String path = uri.getPath().replaceFirst("^/", "").replaceFirst("\\.git$", "");
        return new ValidationResult(normalizeHost(uri.getHost()), path, branch,
                sha.toLowerCase(Locale.ROOT), true);
    }

    /** 仓库不存在时 clone；已存在时校验其 origin 与配置 URL 一致。 */
    public void ensureRepository(GitLabManagedProject project, String accessToken) {
        validateCloneUrl(project.cloneUrl());
        validateBranch(project.branch());
        Path repository = repositoryPath(project.projectId());
        if (Files.exists(repository.resolve(".git"))) {
            String origin = runGit(repository, accessToken, "config", "--get", "remote.origin.url");
            if (!project.cloneUrl().equals(origin.trim())) {
                throw new IllegalStateException("本地仓库 origin 与接入配置不一致");
            }
            return;
        }
        if (Files.exists(repository)) {
            try (var entries = Files.list(repository)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalStateException("项目仓库目录已存在且不是 Git 仓库");
                }
            } catch (IOException exception) {
                throw new IllegalStateException("无法检查项目仓库目录", exception);
            }
        }
        runGit(repositoryRoot, accessToken, "clone", "--origin", "origin", "--no-checkout",
                "--branch", project.branch(), "--", project.cloneUrl(), repository.toString());
    }

    /** 仅拉取配置分支到 origin 远端引用。 */
    public void fetch(GitLabManagedProject project, String accessToken) {
        validateBranch(project.branch());
        runGit(repositoryPath(project.projectId()), accessToken, "fetch", "--prune", "origin",
                "+refs/heads/" + project.branch() + ":refs/remotes/origin/" + project.branch());
    }

    /** 返回已 fetch 的目标分支 40 位 commit SHA。 */
    public String remoteHead(GitLabManagedProject project) {
        String sha = runGit(repositoryPath(project.projectId()), null,
                "rev-parse", "--verify", "refs/remotes/origin/" + project.branch()).trim();
        validateSha(sha);
        return sha.toLowerCase(java.util.Locale.ROOT);
    }

    /** 判断 oldSha 是否为 newSha 的祖先。 */
    public boolean isAncestor(String projectId, String oldSha, String newSha) {
        validateSha(oldSha);
        validateSha(newSha);
        CommandResult result = execute(repositoryPath(projectId), null,
                List.of("merge-base", "--is-ancestor", oldSha, newSha));
        if (result.exitCode() == 0) {
            return true;
        }
        if (result.exitCode() == 1) {
            return false;
        }
        throw new IllegalStateException("无法验证 Git 提交关系");
    }

    /** 以 detached HEAD 检出指定 commit，保证索引内容与 commit 快照一致。 */
    public void checkout(String projectId, String sha) {
        validateSha(sha);
        runGit(repositoryPath(projectId), null, "checkout", "--detach", "--force", sha);
        runGit(repositoryPath(projectId), null, "clean", "-fd");
    }

    static void validateProjectId(String projectId) {
        if (projectId == null || !PROJECT_ID.matcher(projectId).matches()) {
            throw new IllegalArgumentException("projectId 仅允许 1-64 位字母、数字、点、下划线和连字符");
        }
    }

    static void validateSha(String sha) {
        if (sha == null || !SHA.matcher(sha).matches()) {
            throw new IllegalArgumentException("Git commit 必须是 40 位十六进制 SHA");
        }
    }

    static void validateBranch(String branch) {
        if (branch == null || branch.isBlank() || branch.length() > 200
                || branch.startsWith("-") || branch.startsWith(".") || branch.endsWith(".")
                || branch.endsWith("/") || branch.endsWith(".lock") || branch.contains("..")
                || branch.contains("@{") || branch.contains("\\") || branch.contains(" ")
                || branch.chars().anyMatch(ch -> ch < 32 || ch == 127
                || ch == '~' || ch == '^' || ch == ':' || ch == '?' || ch == '*' || ch == '[')) {
            throw new IllegalArgumentException("Git 分支名无效");
        }
    }

    void validateCloneUrl(String cloneUrl) {
        try {
            URI uri = new URI(cloneUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getHost().isBlank() || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPath() == null || uri.getPath().isBlank()) {
                throw new IllegalArgumentException("GitLab cloneUrl 必须是不含凭据、查询参数和片段的 HTTPS URL");
            }
            String host = normalizeHost(uri.getHost());
            if (!allowedHosts.contains(host)) {
                throw new IllegalArgumentException("GitLab cloneUrl 主机不在 allowedHosts 白名单中");
            }
            InetAddress[] addresses = resolve(host);
            if (!allowPrivateHosts && (isIpLiteral(host)
                    || java.util.Arrays.stream(addresses).anyMatch(GitLabGitClient::isUnsafeAddress))) {
                throw new IllegalArgumentException("GitLab cloneUrl 默认禁止 IP、回环和内网地址");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("GitLab cloneUrl 格式无效");
        }
    }

    private String normalizeHost(String host) {
        String unwrapped = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        try {
            return IDN.toASCII(unwrapped).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("GitLab cloneUrl 主机名无效");
        }
    }

    private InetAddress[] resolve(String host) {
        try {
            InetAddress[] addresses = addressResolver.resolve(host);
            if (addresses == null || addresses.length == 0) {
                throw new IllegalArgumentException("GitLab cloneUrl 主机无法解析");
            }
            return addresses;
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("GitLab cloneUrl 主机无法解析");
        }
    }

    private boolean isIpLiteral(String host) {
        if (host.contains(":")) {
            return true;
        }
        if (!IPV4_LITERAL.matcher(host).matches()) {
            return false;
        }
        return java.util.Arrays.stream(host.split("\\."))
                .mapToInt(Integer::parseInt)
                .allMatch(part -> part >= 0 && part <= 255);
    }

    private static boolean isUnsafeAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean ipv6UniqueLocal = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || ipv6UniqueLocal;
    }

    private String runGit(Path workingDirectory, String accessToken, String... arguments) {
        CommandResult result = execute(workingDirectory, accessToken, List.of(arguments));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Git 操作失败");
        }
        return result.output();
    }

    private CommandResult execute(Path workingDirectory, String accessToken, List<String> arguments) {
        Path askpass = null;
        Process process = null;
        Future<byte[]> output = null;
        try {
            List<String> command = new ArrayList<>(arguments.size() + 1);
            command.add("git");
            command.addAll(arguments);
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true);
            Map<String, String> environment = builder.environment();
            environment.put("GIT_TERMINAL_PROMPT", "0");
            if (accessToken != null) {
                if (accessToken.isBlank()) {
                    throw new IllegalArgumentException("GitLab accessToken 不能为空");
                }
                askpass = createAskPass();
                environment.put("GIT_ASKPASS", askpass.toString());
                environment.put("NEXUS_GIT_USERNAME", "oauth2");
                environment.put("NEXUS_GIT_TOKEN", accessToken);
            }
            process = builder.start();
            Process started = process;
            output = outputExecutor.submit(() -> readOutput(started.getInputStream()));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Git 操作超时");
            }
            byte[] bytes = output.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            return new CommandResult(process.exitValue(), new String(bytes, StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git 操作被中断");
        } catch (IOException | java.util.concurrent.ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Git 操作不可用");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (output != null && !output.isDone()) {
                output.cancel(true);
            }
            if (askpass != null) {
                try {
                    Files.deleteIfExists(askpass);
                } catch (IOException ignored) {
                    // 临时脚本不包含凭据；删除失败不覆盖原始 Git 结果。
                }
            }
        }
    }

    private Path createAskPass() throws IOException {
        Path script = Files.createTempFile("nexus-git-askpass-", ".sh");
        Files.writeString(script, """
                #!/bin/sh
                case "$1" in
                  *Username*) printf '%s' "$NEXUS_GIT_USERNAME" ;;
                  *) printf '%s' "$NEXUS_GIT_TOKEN" ;;
                esac
                """, StandardCharsets.UTF_8);
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(script, permissions);
        } catch (UnsupportedOperationException exception) {
            if (!script.toFile().setExecutable(true, true)) {
                throw new IOException("无法设置 GIT_ASKPASS 执行权限");
            }
        }
        return script;
    }

    private byte[] readOutput(InputStream input) throws IOException {
        int limit = 1_048_576;
        byte[] buffer = new byte[8192];
        ByteArrayOutputStream retained = new ByteArrayOutputStream(Math.min(limit, 8192));
        int read;
        while ((read = input.read(buffer)) != -1) {
            int remaining = limit - retained.size();
            if (remaining > 0) {
                retained.write(buffer, 0, Math.min(read, remaining));
            }
        }
        return retained.toByteArray();
    }

    @PreDestroy
    void shutdown() {
        outputExecutor.shutdownNow();
    }

    private record CommandResult(int exitCode, String output) {
    }

    @FunctionalInterface
    interface AddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public record ValidationResult(
            String host,
            String repositoryPath,
            String branch,
            String headSha,
            boolean readable
    ) {
    }
}
