package com.example.requirementrag.integration.gitlab;

import com.example.requirementrag.config.ProjectRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** GitLab 账号连接、验证和项目发现服务。 */
@Service
@ConditionalOnProperty(name = "app.rag.gitlab.enabled", havingValue = "true")
public class GitLabAccountService {
    private final GitLabConnectionStore connectionStore;
    private final GitLabProjectStore projectStore;
    private final GitLabCredentialCipher cipher;
    private final GitLabApiClient apiClient;
    private final GitLabHostPolicy hostPolicy;
    private final ProjectRegistry projectRegistry;

    public GitLabAccountService(GitLabConnectionStore connectionStore,
                                GitLabProjectStore projectStore,
                                GitLabCredentialCipher cipher,
                                GitLabApiClient apiClient,
                                GitLabHostPolicy hostPolicy,
                                ProjectRegistry projectRegistry) {
        this.connectionStore = connectionStore;
        this.projectStore = projectStore;
        this.cipher = cipher;
        this.apiClient = apiClient;
        this.hostPolicy = hostPolicy;
        this.projectRegistry = projectRegistry;
    }

    public GitLabConnection.View create(CreateConnection request) {
        validate(request);
        if (connectionStore.all().stream().anyMatch(value -> value.name().equalsIgnoreCase(request.name().trim()))) {
            throw new IllegalArgumentException("GitLab 账号连接名称已存在");
        }
        URI baseUrl = hostPolicy.validateBaseUrl(request.baseUrl());
        GitLabApiClient.Account account = apiClient.account(baseUrl.toString(), request.accessToken());
        String now = Instant.now().toString();
        GitLabConnection connection = new GitLabConnection(
                UUID.randomUUID().toString(),
                request.name().trim(),
                baseUrl.toString(),
                hostPolicy.normalizeHost(baseUrl.getHost()),
                account.username(),
                text(account.name(), account.username()),
                cipher.encrypt(request.accessToken()),
                GitLabConnectionStatus.ACTIVE,
                now,
                null,
                now,
                now);
        connectionStore.save(connection);
        return connection.toView();
    }

    public List<GitLabConnection.View> list() {
        return connectionStore.all().stream().map(GitLabConnection::toView).toList();
    }

    public GitLabConnection.View require(String id) {
        return connection(id).toView();
    }

    public GitLabConnection.View verify(String id) {
        GitLabConnection connection = connection(id);
        requireNotDisabled(connection);
        try {
            GitLabApiClient.Account account = apiClient.account(
                    connection.baseUrl(), cipher.decrypt(connection.encryptedAccessToken()));
            String now = Instant.now().toString();
            GitLabConnection updated = new GitLabConnection(
                    connection.id(), connection.name(), connection.baseUrl(), connection.host(),
                    account.username(), text(account.name(), account.username()),
                    connection.encryptedAccessToken(), GitLabConnectionStatus.ACTIVE,
                    now, null, connection.createdAt(), now);
            connectionStore.save(updated);
            return updated.toView();
        } catch (GitLabApiException exception) {
            markInvalidIfCredentialError(id, exception);
            throw exception;
        }
    }

    public GitLabConnection.View reauthorize(String id, Reauthorize request) {
        if (request == null || request.accessToken() == null || request.accessToken().isBlank()) {
            throw new IllegalArgumentException("Personal Access Token 不能为空");
        }
        GitLabConnection connection = connection(id);
        GitLabApiClient.Account account = apiClient.account(connection.baseUrl(), request.accessToken());
        String now = Instant.now().toString();
        GitLabConnection updated = new GitLabConnection(
                connection.id(), connection.name(), connection.baseUrl(), connection.host(),
                account.username(), text(account.name(), account.username()),
                cipher.encrypt(request.accessToken()), GitLabConnectionStatus.ACTIVE,
                now, null, connection.createdAt(), now);
        connectionStore.save(updated);
        return updated.toView();
    }

    public GitLabConnection.View disable(String id) {
        connection(id);
        connectionStore.updateStatus(id, GitLabConnectionStatus.DISABLED, null);
        return require(id);
    }

    public ProjectPage projects(String connectionId, int page, int size, String query) {
        GitLabConnection connection = activeConnection(connectionId);
        List<GitLabApiClient.RemoteProject> remote;
        try {
            remote = apiClient.membershipProjects(
                    connection.baseUrl(), cipher.decrypt(connection.encryptedAccessToken()), query);
        } catch (GitLabApiException exception) {
            markInvalidIfCredentialError(connectionId, exception);
            throw exception;
        }
        List<GitLabManagedProject> connectedProjects = projectStore.findByConnectionId(connectionId);
        Map<Long, GitLabManagedProject> imported = connectedProjects.stream()
                .filter(project -> project.remoteProjectId() != null)
                .collect(Collectors.toMap(
                        GitLabManagedProject::remoteProjectId, Function.identity(), (left, right) -> left));
        Map<String, GitLabManagedProject> legacyImported = connectedProjects.stream()
                .filter(project -> project.remoteProjectId() == null)
                .collect(Collectors.toMap(
                        GitLabManagedProject::gitPath, Function.identity(), (left, right) -> left));
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<RemoteProjectView> values = remote.stream()
                .filter(project -> normalizedQuery.isEmpty()
                        || contains(project.name(), normalizedQuery)
                        || contains(project.pathWithNamespace(), normalizedQuery))
                .map(project -> view(project, imported, legacyImported))
                .sorted(Comparator.comparing(RemoteProjectView::lastActivityAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size <= 0 ? 50 : size, 100));
        int from = (int) Math.min((long) safePage * safeSize, values.size());
        int to = Math.min(from + safeSize, values.size());
        return new ProjectPage(values.subList(from, to), safePage, safeSize, values.size());
    }

    public List<BranchView> branches(String connectionId, long remoteProjectId) {
        GitLabConnection connection = activeConnection(connectionId);
        try {
            return apiClient.branches(
                            connection.baseUrl(),
                            cipher.decrypt(connection.encryptedAccessToken()),
                            remoteProjectId)
                    .stream()
                    .map(branch -> new BranchView(
                            branch.name(), branch.defaultBranch(),
                            branch.protectedBranch(), branch.merged()))
                    .toList();
        } catch (GitLabApiException exception) {
            markInvalidIfCredentialError(connectionId, exception);
            throw exception;
        }
    }

    GitLabConnection activeConnection(String id) {
        GitLabConnection connection = connection(id);
        requireNotDisabled(connection);
        if (connection.status() == GitLabConnectionStatus.INVALID) {
            throw new IllegalArgumentException("GitLab 账号连接已失效，请重新授权");
        }
        return connection;
    }

    String accessToken(GitLabConnection connection) {
        return cipher.decrypt(connection.encryptedAccessToken());
    }

    void markInvalidIfCredentialError(String connectionId, GitLabApiException exception) {
        if ("GITLAB_TOKEN_INVALID".equals(exception.code())) {
            connectionStore.updateStatus(connectionId, GitLabConnectionStatus.INVALID,
                    exception.getMessage());
        }
    }

    private RemoteProjectView view(GitLabApiClient.RemoteProject project,
                                   Map<Long, GitLabManagedProject> imported,
                                   Map<String, GitLabManagedProject> legacyImported) {
        GitLabManagedProject existing = imported.get(project.id());
        if (existing == null) {
            existing = legacyImported.get(project.pathWithNamespace());
        }
        String generatedProjectId = projectId(project.pathWithNamespace(), project.id());
        ImportState state;
        if (existing != null) state = ImportState.IMPORTED;
        else if (project.archived()) state = ImportState.ARCHIVED;
        else if (project.defaultBranch() == null || project.defaultBranch().isBlank()) {
            state = ImportState.NO_DEFAULT_BRANCH;
        } else if (conflicts(generatedProjectId)) {
            state = ImportState.CONFLICT;
        } else state = ImportState.AVAILABLE;
        String prefix = generatedProjectId.replace('.', '_').replace('-', '_');
        return new RemoteProjectView(
                project.id(), project.name(), project.pathWithNamespace(), project.defaultBranch(),
                project.visibility(), project.archived(), project.lastActivityAt(), state,
                existing == null ? null : existing.projectId(), generatedProjectId, "server",
                prefix + "_code");
    }

    private String projectId(String path, long remoteId) {
        String slug = path.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^[._-]+|[._-]+$", "");
        if (slug.isBlank()) slug = "gitlab-project-" + remoteId;
        if (slug.length() > 64) slug = slug.substring(0, 64);
        if (conflicts(slug)) {
            String suffix = "-" + remoteId;
            slug = slug.substring(0, Math.min(slug.length(), 64 - suffix.length())) + suffix;
        }
        return slug;
    }

    private boolean conflicts(String projectId) {
        return projectRegistry.isStaticProject(projectId)
                || projectStore.find(projectId).isPresent();
    }

    private GitLabConnection connection(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("connectionId 不能为空");
        return connectionStore.find(id)
                .orElseThrow(() -> new IllegalArgumentException("未知 GitLab 账号连接: " + id));
    }

    private void requireNotDisabled(GitLabConnection connection) {
        if (connection.status() == GitLabConnectionStatus.DISABLED) {
            throw new IllegalArgumentException("GitLab 账号连接已停用");
        }
    }

    private void validate(CreateConnection request) {
        if (request == null) throw new IllegalArgumentException("请求不能为空");
        if (request.name() == null || request.name().isBlank() || request.name().trim().length() > 100) {
            throw new IllegalArgumentException("连接名称必须为 1-100 位");
        }
        if (request.accessToken() == null || request.accessToken().isBlank()) {
            throw new IllegalArgumentException("Personal Access Token 不能为空");
        }
        hostPolicy.validateBaseUrl(request.baseUrl());
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record CreateConnection(String name, String baseUrl, String accessToken) {
    }

    public record Reauthorize(String accessToken) {
    }

    public record ProjectPage(List<RemoteProjectView> items, int page, int size, long total) {
        public ProjectPage {
            items = List.copyOf(items);
        }
    }

    public enum ImportState { AVAILABLE, IMPORTED, ARCHIVED, NO_DEFAULT_BRANCH, CONFLICT }

    public record RemoteProjectView(
            long remoteProjectId,
            String name,
            String pathWithNamespace,
            String defaultBranch,
            String visibility,
            boolean archived,
            String lastActivityAt,
            ImportState importState,
            String importedProjectId,
            String projectId,
            String side,
            String codeCollection
    ) {
    }

    public record BranchView(
            String name,
            boolean defaultBranch,
            boolean protectedBranch,
            boolean merged
    ) {
    }
}
