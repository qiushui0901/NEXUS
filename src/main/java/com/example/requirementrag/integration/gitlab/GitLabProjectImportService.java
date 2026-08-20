package com.example.requirementrag.integration.gitlab;

import com.example.requirementrag.project.BusinessProjectCatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 从账号项目发现结果批量导入 NEXUS 项目。 */
@Service
@ConditionalOnProperty(name = "app.rag.gitlab.enabled", havingValue = "true")
public class GitLabProjectImportService {
    private static final int MAX_BATCH = 100;
    private final GitLabAccountService accountService;
    private final GitLabApiClient apiClient;
    private final GitLabGitClient gitClient;
    private final GitLabProjectStore projectStore;
    private final GitLabSyncService syncService;
    private final BusinessProjectCatalogService catalogService;

    @Autowired
    public GitLabProjectImportService(GitLabAccountService accountService,
                                      GitLabApiClient apiClient,
                                      GitLabGitClient gitClient,
                                      GitLabProjectStore projectStore,
                                      GitLabSyncService syncService,
                                      BusinessProjectCatalogService catalogService) {
        this.accountService = accountService;
        this.apiClient = apiClient;
        this.gitClient = gitClient;
        this.projectStore = projectStore;
        this.syncService = syncService;
        this.catalogService = catalogService;
    }

    GitLabProjectImportService(GitLabAccountService accountService,
                               GitLabApiClient apiClient,
                               GitLabGitClient gitClient,
                               GitLabProjectStore projectStore,
                               GitLabSyncService syncService) {
        this(accountService, apiClient, gitClient, projectStore, syncService, null);
    }

    public BatchImportResponse importProjects(String connectionId, BatchImportRequest request) {
        if (request == null || request.projects() == null || request.projects().isEmpty()) {
            throw new IllegalArgumentException("至少选择一个 GitLab 项目");
        }
        if (request.projects().size() > MAX_BATCH) {
            throw new IllegalArgumentException("单次最多导入 " + MAX_BATCH + " 个项目");
        }
        String businessProjectId = request.businessProjectId();
        if (catalogService != null) {
            businessProjectId = catalogService.requireImportTarget(businessProjectId).id();
        }
        Set<Long> requestedIds = new HashSet<>();
        for (ImportProject item : request.projects()) {
            if (item == null || item.remoteProjectId() <= 0
                    || !requestedIds.add(item.remoteProjectId())) {
                throw new IllegalArgumentException("remoteProjectId 必须为不重复的正数");
            }
        }

        GitLabConnection connection = accountService.activeConnection(connectionId);
        String token = accountService.accessToken(connection);
        List<Future<ResolvedProject>> pending = new ArrayList<>(request.projects().size());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ImportProject item : request.projects()) {
                pending.add(executor.submit(() -> resolve(connection, token, item)));
            }
        }

        List<ImportResult> results = new ArrayList<>();
        for (int index = 0; index < request.projects().size(); index++) {
            ImportProject item = request.projects().get(index);
            ResolvedProject resolved = await(pending.get(index), item);
            if (resolved.failure() != null) {
                if (resolved.failure() instanceof GitLabApiException apiException) {
                    accountService.markInvalidIfCredentialError(connectionId, apiException);
                }
                results.add(failed(item, resolved.code(), resolved.message()));
                continue;
            }
            try {
                GitLabApiClient.RemoteProject remote = resolved.remote();
                if (!remote.member()) {
                    throw new ImportFailure("GITLAB_PROJECT_NOT_MEMBER",
                            "项目不属于当前 GitLab 账号");
                }
                if (remote.archived()) {
                    throw new ImportFailure("GITLAB_PROJECT_ARCHIVED", "归档项目不能导入");
                }
                if (projectStore.findByRemoteProject(connection.id(), remote.id()).isPresent()) {
                    throw new ImportFailure("GITLAB_PROJECT_ALREADY_IMPORTED", "GitLab 项目已经导入");
                }
                String branch = text(item.branch(), remote.defaultBranch());
                if (branch == null || branch.isBlank()) {
                    throw new ImportFailure("GITLAB_DEFAULT_BRANCH_MISSING", "项目没有默认分支");
                }
                if (resolved.branches().stream().noneMatch(value -> branch.equals(value.name()))) {
                    throw new ImportFailure("GITLAB_BRANCH_NOT_FOUND", "所选分支不存在或已被删除");
                }
                String webhookSecret = secret();
                boolean catalogRegistered = false;
                try {
                    if (catalogService != null) {
                        catalogService.registerOwnedRepository(businessProjectId, item.projectId(), remote.name(),
                                text(item.side(), "server"), item.codeCollection(),
                                gitClient.repositoryPath(item.projectId()).toString(), remote.pathWithNamespace());
                        catalogRegistered = true;
                    }
                    GitLabManagedProject.View project = syncService.registerConnected(
                            new GitLabSyncService.CreateConnectedProject(
                                    connection.id(),
                                    remote.id(),
                                    item.projectId(),
                                    remote.name(),
                                    group(remote.pathWithNamespace()),
                                    text(item.side(), "server"),
                                    remote.httpUrlToRepo(),
                                    branch,
                                    remote.pathWithNamespace(),
                                    item.codeCollection(),
                                    webhookSecret));
                    results.add(new ImportResult(
                            remote.id(), remote.pathWithNamespace(), item.projectId(),
                            "ACCEPTED", null, null, project,
                            webhookSecret, "/api/webhooks/gitlab/" + project.projectId()));
                } catch (RuntimeException exception) {
                    if (catalogRegistered) catalogService.unregisterRepository(item.projectId());
                    throw exception;
                }
            } catch (ImportFailure exception) {
                results.add(failed(item, exception.code, exception.getMessage()));
            } catch (GitLabApiException exception) {
                accountService.markInvalidIfCredentialError(connectionId, exception);
                results.add(failed(item, exception.code(), exception.getMessage()));
            } catch (IllegalArgumentException exception) {
                results.add(failed(item, "GITLAB_IMPORT_INVALID", safe(exception.getMessage())));
            } catch (RuntimeException exception) {
                results.add(failed(item, "GITLAB_IMPORT_FAILED", "项目导入失败，请稍后重试"));
            }
        }
        long accepted = results.stream().filter(result -> "ACCEPTED".equals(result.status())).count();
        return new BatchImportResponse(List.copyOf(results), accepted, results.size() - accepted);
    }

    private ResolvedProject resolve(GitLabConnection connection, String token, ImportProject item) {
        try {
            GitLabApiClient.RemoteProject remote =
                    apiClient.project(connection.baseUrl(), token, item.remoteProjectId());
            List<GitLabApiClient.RemoteBranch> branches =
                    !remote.member() || remote.archived() ? List.of()
                            : apiClient.branches(connection.baseUrl(), token, item.remoteProjectId());
            return new ResolvedProject(remote, branches, null, null, null);
        } catch (GitLabApiException exception) {
            return new ResolvedProject(null, List.of(),
                    exception, exception.code(), exception.getMessage());
        } catch (RuntimeException exception) {
            return new ResolvedProject(null, List.of(), exception,
                    "GITLAB_IMPORT_FAILED", "项目详情或分支读取失败，请稍后重试");
        }
    }

    private ResolvedProject await(Future<ResolvedProject> pending, ImportProject item) {
        try {
            return pending.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ResolvedProject(null, List.of(), exception,
                    "GITLAB_IMPORT_INTERRUPTED", "项目导入被中断");
        } catch (ExecutionException exception) {
            return new ResolvedProject(null, List.of(), exception,
                    "GITLAB_IMPORT_FAILED", "项目详情或分支读取失败，请稍后重试");
        }
    }

    private ImportResult failed(ImportProject item, String code, String message) {
        return new ImportResult(item.remoteProjectId(), null, item.projectId(),
                "FAILED", code, message, null, null, null);
    }

    private String group(String path) {
        int slash = path == null ? -1 : path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : "default";
    }

    private String secret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String safe(String message) {
        if (message == null || message.isBlank()) return "项目导入配置无效";
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record BatchImportRequest(String businessProjectId, List<ImportProject> projects) {
        public BatchImportRequest {
            projects = projects == null ? List.of() : List.copyOf(projects);
        }

        public BatchImportRequest(List<ImportProject> projects) {
            this(null, projects);
        }
    }

    public record ImportProject(
            long remoteProjectId,
            String projectId,
            String side,
            String branch,
            String codeCollection
    ) {
    }

    public record BatchImportResponse(List<ImportResult> results, long accepted, long failed) {
    }

    public record ImportResult(
            long remoteProjectId,
            String pathWithNamespace,
            String projectId,
            String status,
            String errorCode,
            String errorMessage,
            GitLabManagedProject.View project,
            String webhookSecret,
            String webhookPath
    ) {
    }

    private static final class ImportFailure extends RuntimeException {
        private final String code;

        private ImportFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private record ResolvedProject(
            GitLabApiClient.RemoteProject remote,
            List<GitLabApiClient.RemoteBranch> branches,
            Exception failure,
            String code,
            String message
    ) {
    }
}
