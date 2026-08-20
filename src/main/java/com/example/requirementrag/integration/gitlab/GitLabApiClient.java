package com.example.requirementrag.integration.gitlab;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 只读 GitLab REST API 客户端，用于账号验证和项目发现。 */
@Component
@ConditionalOnProperty(name = "app.rag.gitlab.enabled", havingValue = "true")
public class GitLabApiClient {
    private static final Logger log = LoggerFactory.getLogger(GitLabApiClient.class);
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 100;
    private final GitLabHostPolicy hostPolicy;
    private final ObjectMapper objectMapper;
    private final ClientFactory clientFactory;

    @Autowired
    public GitLabApiClient(GitLabHostPolicy hostPolicy, ObjectMapper objectMapper) {
        this(hostPolicy, objectMapper, GitLabApiClient::client);
    }

    GitLabApiClient(GitLabHostPolicy hostPolicy, ClientFactory clientFactory) {
        this(hostPolicy, new ObjectMapper(), clientFactory);
    }

    GitLabApiClient(GitLabHostPolicy hostPolicy, ObjectMapper objectMapper,
                    ClientFactory clientFactory) {
        this.hostPolicy = hostPolicy;
        this.objectMapper = objectMapper;
        this.clientFactory = clientFactory;
    }

    public Account account(String baseUrl, String accessToken) {
        requireToken(accessToken);
        URI normalized = hostPolicy.validateBaseUrl(baseUrl);
        try {
            Account value = clientFactory.create(apiBase(normalized))
                    .get().uri("/user")
                    .header("PRIVATE-TOKEN", accessToken)
                    .retrieve().body(Account.class);
            if (value == null || value.id() <= 0 || blank(value.username())) {
                throw new GitLabApiException("GITLAB_INVALID_RESPONSE", "GitLab 账号响应无效");
            }
            return value;
        } catch (GitLabApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw map(exception, true);
        }
    }

    public List<RemoteProject> membershipProjects(String baseUrl, String accessToken) {
        return membershipProjects(baseUrl, accessToken, null);
    }

    public List<RemoteProject> membershipProjects(String baseUrl, String accessToken, String query) {
        requireToken(accessToken);
        URI normalized = hostPolicy.validateBaseUrl(baseUrl);
        RestClient client = clientFactory.create(apiBase(normalized));
        List<RemoteProject> projects = new ArrayList<>();
        String search = query == null ? "" : query.trim();
        String page = "1";
        for (int pages = 0; pages < MAX_PAGES && !blank(page); pages++) {
            ResponseEntity<String> response;
            try {
                String currentPage = page;
                response = client.get()
                        .uri(builder -> builder.path("/projects")
                                .queryParam("membership", true)
                                .queryParam("simple", true)
                                .queryParam("per_page", PAGE_SIZE)
                                .queryParam("page", currentPage)
                                .queryParam("order_by", "last_activity_at")
                                .queryParam("sort", "desc")
                                .queryParamIfPresent("search", search.isEmpty()
                                        ? java.util.Optional.empty()
                                        : java.util.Optional.of(search))
                                .build())
                        .header("PRIVATE-TOKEN", accessToken)
                        .retrieve().toEntity(String.class);
            } catch (RuntimeException exception) {
                logDiscoveryFailure(exception);
                throw map(exception, false);
            }
            JsonNode body = projectArray(parse(response.getBody()));
            for (JsonNode value : body) {
                RemoteProject project = remoteProject(value);
                if (project.id() > 0 && !blank(project.pathWithNamespace())) {
                    projects.add(project);
                }
            }
            page = response.getHeaders().getFirst("X-Next-Page");
        }
        if (!blank(page)) {
            throw new GitLabApiException("GITLAB_PROJECT_LIMIT",
                    "GitLab 项目数量超过单次发现上限，请使用搜索缩小范围");
        }
        return List.copyOf(projects);
    }

    public RemoteProject project(String baseUrl, String accessToken, long projectId) {
        requireToken(accessToken);
        if (projectId <= 0) throw new IllegalArgumentException("remoteProjectId 必须为正数");
        URI normalized = hostPolicy.validateBaseUrl(baseUrl);
        try {
            String response = clientFactory.create(apiBase(normalized))
                    .get().uri("/projects/{id}", projectId)
                    .header("PRIVATE-TOKEN", accessToken)
                    .retrieve().body(String.class);
            RemoteProject value = remoteProject(projectObject(parse(response)));
            if (value == null || value.id() <= 0 || blank(value.pathWithNamespace())
                    || blank(value.httpUrlToRepo())) {
                throw new GitLabApiException("GITLAB_INVALID_RESPONSE", "GitLab 项目响应无效");
            }
            hostPolicy.validateCloneUrlForBaseUrl(value.httpUrlToRepo(), normalized.toString());
            return value;
        } catch (GitLabApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw map(exception, false);
        }
    }

    public List<RemoteBranch> branches(String baseUrl, String accessToken, long projectId) {
        requireToken(accessToken);
        if (projectId <= 0) throw new IllegalArgumentException("remoteProjectId 必须为正数");
        URI normalized = hostPolicy.validateBaseUrl(baseUrl);
        RestClient client = clientFactory.create(apiBase(normalized));
        List<RemoteBranch> branches = new ArrayList<>();
        String page = "1";
        for (int pages = 0; pages < MAX_PAGES && !blank(page); pages++) {
            ResponseEntity<String> response;
            try {
                String currentPage = page;
                response = client.get()
                        .uri(builder -> builder.path("/projects/{id}/repository/branches")
                                .queryParam("per_page", PAGE_SIZE)
                                .queryParam("page", currentPage)
                                .build(projectId))
                        .header("PRIVATE-TOKEN", accessToken)
                        .retrieve().toEntity(String.class);
            } catch (RuntimeException exception) {
                logDiscoveryFailure(exception);
                throw map(exception, false);
            }
            for (JsonNode value : projectArray(parse(response.getBody()))) {
                RemoteBranch branch = remoteBranch(value);
                if (!blank(branch.name())) branches.add(branch);
            }
            page = response.getHeaders().getFirst("X-Next-Page");
        }
        if (!blank(page)) {
            throw new GitLabApiException("GITLAB_BRANCH_LIMIT",
                    "GitLab 分支数量超过单次读取上限");
        }
        return branches.stream()
                .sorted(java.util.Comparator.comparing(RemoteBranch::defaultBranch).reversed()
                        .thenComparing(RemoteBranch::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private GitLabApiException map(RuntimeException exception, boolean credentialVerification) {
        if (exception instanceof HttpClientErrorException.Unauthorized) {
            return new GitLabApiException("GITLAB_TOKEN_INVALID",
                    "GitLab Personal Access Token 无效或权限不足");
        }
        if (exception instanceof HttpClientErrorException.Forbidden) {
            return credentialVerification
                    ? new GitLabApiException("GITLAB_TOKEN_INVALID",
                    "GitLab Personal Access Token 无效或权限不足")
                    : new GitLabApiException("GITLAB_PERMISSION_DENIED",
                    "当前 GitLab 项目或仓库权限不足");
        }
        if (exception instanceof HttpClientErrorException.NotFound) {
            return new GitLabApiException("GITLAB_PROJECT_NOT_FOUND", "GitLab 项目不存在或无权访问");
        }
        if (exception instanceof RestClientResponseException response && response.getStatusCode().value() == 429) {
            return new GitLabApiException("GITLAB_RATE_LIMITED", "GitLab API 请求过于频繁，请稍后重试");
        }
        if (exception instanceof ResourceAccessException) {
            return new GitLabApiException("GITLAB_API_UNAVAILABLE", "GitLab API 连接超时或不可用");
        }
        if (exception instanceof RestClientResponseException response
                && response.getStatusCode().is5xxServerError()) {
            return new GitLabApiException("GITLAB_API_UNAVAILABLE", "GitLab API 暂时不可用");
        }
        if (exception instanceof RestClientException) {
            return new GitLabApiException("GITLAB_INVALID_RESPONSE", "GitLab API 响应无法解析");
        }
        if (exception instanceof IllegalArgumentException argument) {
            throw argument;
        }
        return new GitLabApiException("GITLAB_API_UNAVAILABLE", "GitLab API 暂时不可用");
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            throw new GitLabApiException("GITLAB_INVALID_RESPONSE", "GitLab 项目响应无效");
        }
        try {
            return objectMapper.readTree(body);
        } catch (java.io.IOException exception) {
            throw new GitLabApiException("GITLAB_INVALID_RESPONSE", "GitLab API 响应无法解析");
        }
    }

    private JsonNode projectArray(JsonNode body) {
        if (body == null || body.isNull()) {
            throw new GitLabApiException("GITLAB_INVALID_RESPONSE", "GitLab 项目响应无效");
        }
        if (body.isArray()) return body;
        for (String field : List.of("data", "items", "projects")) {
            JsonNode nested = body.path(field);
            if (nested.isArray()) return nested;
        }
        throw new GitLabApiException("GITLAB_INVALID_RESPONSE", "GitLab 项目响应无效");
    }

    private JsonNode projectObject(JsonNode body) {
        if (body == null || body.isNull()) {
            throw new GitLabApiException("GITLAB_INVALID_RESPONSE", "GitLab 项目响应无效");
        }
        if (body.isObject() && body.path("id").canConvertToLong()) return body;
        JsonNode nested = body.path("data");
        if (nested.isObject()) return nested;
        throw new GitLabApiException("GITLAB_INVALID_RESPONSE", "GitLab 项目响应无效");
    }

    private RemoteProject remoteProject(JsonNode value) {
        if (value == null || !value.isObject()) {
            return new RemoteProject(0, null, null, null, null,
                    null, false, null, null);
        }
        return new RemoteProject(
                value.path("id").asLong(),
                text(value, "name"),
                text(value, "path_with_namespace"),
                text(value, "http_url_to_repo"),
                text(value, "default_branch"),
                text(value, "visibility"),
                value.path("archived").asBoolean(false),
                text(value, "last_activity_at"),
                permissions(value.path("permissions")));
    }

    private RemoteBranch remoteBranch(JsonNode value) {
        if (value == null || !value.isObject()) {
            return new RemoteBranch(null, false, false, false, null);
        }
        return new RemoteBranch(
                text(value, "name"),
                value.path("default").asBoolean(false),
                value.path("protected").asBoolean(false),
                value.path("merged").asBoolean(false),
                text(value.path("commit"), "id"));
    }

    private Permissions permissions(JsonNode value) {
        if (value == null || !value.isObject()) return null;
        return new Permissions(
                access(value.path("project_access")),
                access(value.path("group_access")));
    }

    private ProjectAccess access(JsonNode value) {
        if (value == null || !value.isObject()) return null;
        return new ProjectAccess(value.path("access_level").asInt());
    }

    private String text(JsonNode value, String field) {
        JsonNode node = value.path(field);
        return node.isTextual() || node.isNumber() || node.isBoolean()
                ? node.asText() : null;
    }

    private void logDiscoveryFailure(RuntimeException exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String status = exception instanceof RestClientResponseException response
                ? Integer.toString(response.getStatusCode().value()) : "none";
        log.warn("GitLab project discovery failed exceptionType={} rootCauseType={} httpStatus={}",
                exception.getClass().getSimpleName(), root.getClass().getSimpleName(), status);
    }

    private String apiBase(URI baseUrl) {
        return baseUrl.toString() + "/api/v4";
    }

    private void requireToken(String accessToken) {
        if (blank(accessToken)) {
            throw new IllegalArgumentException("Personal Access Token 不能为空");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static RestClient client(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.USER_AGENT, "NEXUS-gitlab-discovery")
                .requestFactory(factory)
                .build();
    }

    @FunctionalInterface
    interface ClientFactory {
        RestClient create(String baseUrl);
    }

    public record Account(long id, String username, String name) {
    }

    public record RemoteProject(
            long id,
            String name,
            @JsonProperty("path_with_namespace") String pathWithNamespace,
            @JsonProperty("http_url_to_repo") String httpUrlToRepo,
            @JsonProperty("default_branch") String defaultBranch,
            String visibility,
            boolean archived,
            @JsonProperty("last_activity_at") String lastActivityAt,
            Permissions permissions
    ) {
        public RemoteProject(long id, String name, String pathWithNamespace, String httpUrlToRepo,
                             String defaultBranch, String visibility, boolean archived,
                             String lastActivityAt) {
            this(id, name, pathWithNamespace, httpUrlToRepo, defaultBranch, visibility,
                    archived, lastActivityAt, null);
        }

        public boolean member() {
            return permissions != null
                    && (accessLevel(permissions.projectAccess()) > 0
                    || accessLevel(permissions.groupAccess()) > 0);
        }

        private int accessLevel(ProjectAccess access) {
            return access == null ? 0 : access.accessLevel();
        }
    }

    public record Permissions(
            @JsonProperty("project_access") ProjectAccess projectAccess,
            @JsonProperty("group_access") ProjectAccess groupAccess
    ) {
    }

    public record ProjectAccess(@JsonProperty("access_level") int accessLevel) {
    }

    public record RemoteBranch(
            String name,
            boolean defaultBranch,
            boolean protectedBranch,
            boolean merged,
            String commitSha
    ) {
    }
}
