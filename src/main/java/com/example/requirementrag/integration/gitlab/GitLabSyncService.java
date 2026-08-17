package com.example.requirementrag.integration.gitlab;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.code.IncrementalCodeIndexService;
import com.example.requirementrag.config.ProjectRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/** GitLab 项目注册、后台同步和首次全量/后续增量索引的编排服务。 */
@Service
@ConditionalOnProperty(name = "app.rag.gitlab.enabled", havingValue = "true")
public class GitLabSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitLabSyncService.class);
    private static final Pattern COLLECTION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    private final GitLabProjectStore store;
    private final GitLabCredentialCipher cipher;
    private final GitLabGitClient gitClient;
    private final ProjectRegistry projectRegistry;
    private final CodeKnowledgeService codeKnowledgeService;
    private final IncrementalCodeIndexService incrementalIndexService;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, ProjectQueue> queues = new ConcurrentHashMap<>();

    @Autowired
    public GitLabSyncService(GitLabProjectStore store,
                             GitLabCredentialCipher cipher,
                             GitLabGitClient gitClient,
                             ProjectRegistry projectRegistry,
                             CodeKnowledgeService codeKnowledgeService,
                             IncrementalCodeIndexService incrementalIndexService,
                             GitLabIntegrationProperties properties) {
        this(store, cipher, gitClient, projectRegistry, codeKnowledgeService, incrementalIndexService,
                Executors.newFixedThreadPool(properties.syncThreads(), Thread.ofVirtual()
                        .name("gitlab-sync-", 0).factory()));
    }

    GitLabSyncService(GitLabProjectStore store,
                      GitLabCredentialCipher cipher,
                      GitLabGitClient gitClient,
                      ProjectRegistry projectRegistry,
                      CodeKnowledgeService codeKnowledgeService,
                      IncrementalCodeIndexService incrementalIndexService,
                      ExecutorService executor) {
        this.store = store;
        this.cipher = cipher;
        this.gitClient = gitClient;
        this.projectRegistry = projectRegistry;
        this.codeKnowledgeService = codeKnowledgeService;
        this.incrementalIndexService = incrementalIndexService;
        this.executor = executor;
        restoreRegistry();
    }

    /** 保存项目、发布到动态注册表并提交首次同步任务。 */
    public GitLabManagedProject.View register(CreateProject request) {
        validate(request);
        if (projectRegistry.isStaticProject(request.projectId())) {
            throw new IllegalArgumentException("projectId 与静态项目冲突");
        }
        if (store.find(request.projectId()).isPresent()) {
            throw new IllegalArgumentException("GitLab 项目已接入: " + request.projectId());
        }
        String now = Instant.now().toString();
        GitLabManagedProject project = new GitLabManagedProject(
                request.projectId(),
                text(request.name(), request.projectId()),
                text(request.group(), "default"),
                text(request.side(), "server"),
                request.cloneUrl(),
                text(request.branch(), "main"),
                request.gitPath(),
                text(request.requirementCollection(), request.projectId() + "_requirements"),
                text(request.codeCollection(), request.projectId() + "_code"),
                gitClient.repositoryPath(request.projectId()).toString(),
                cipher.encrypt(request.accessToken()),
                cipher.encrypt(request.webhookSecret()),
                GitLabProjectStatus.PENDING,
                null,
                null,
                null,
                now,
                now);
        store.save(project);
        try {
            projectRegistry.registerDynamic(project.toProjectConfig());
            enqueue(project.projectId(), null);
            return project.toView();
        } catch (RuntimeException exception) {
            projectRegistry.unregisterDynamic(project.projectId());
            store.delete(project.projectId());
            throw exception;
        }
    }

    public List<GitLabManagedProject.View> list() {
        return store.all().stream().map(GitLabManagedProject::toView).toList();
    }

    public GitLabManagedProject.View require(String projectId) {
        return requireProject(projectId).toView();
    }

    /** 手动同步配置分支的远端 HEAD。 */
    public GitLabManagedProject.View sync(String projectId) {
        GitLabManagedProject project = requireEnabled(projectId);
        enqueue(project.projectId(), null);
        return require(projectId);
    }

    /** 仅允许失败任务重试，避免把 retry 当作第二套同步语义。 */
    public GitLabManagedProject.View retry(String projectId) {
        GitLabManagedProject project = requireEnabled(projectId);
        if (project.status() != GitLabProjectStatus.FAILED) {
            throw new IllegalArgumentException("只有 FAILED 项目可以重试");
        }
        enqueue(project.projectId(), project.targetSha());
        return require(projectId);
    }

    /** 禁用动态项目，停止新任务并保留仓库、索引和元数据。 */
    public GitLabManagedProject.View disable(String projectId) {
        requireProject(projectId);
        ProjectQueue queue = queues.remove(projectId);
        if (queue != null) {
            synchronized (queue) {
                queue.requests.clear();
                if (queue.worker != null) {
                    queue.worker.cancel(true);
                }
            }
        }
        store.updateState(projectId, GitLabProjectStatus.DISABLED, null, null, null);
        projectRegistry.unregisterDynamic(projectId);
        return require(projectId);
    }

    /** 校验项目级 GitLab 原生 Secret Token。 */
    public GitLabManagedProject authenticateWebhook(String projectId, String providedToken) {
        GitLabManagedProject project = requireEnabled(projectId);
        if (providedToken == null || providedToken.isBlank()) {
            throw new SecurityException("Webhook token 无效");
        }
        byte[] expected = cipher.decrypt(project.encryptedWebhookSecret()).getBytes(StandardCharsets.UTF_8);
        byte[] provided = providedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, provided)) {
            throw new SecurityException("Webhook token 无效");
        }
        return project;
    }

    /** Webhook 事件去重后提交指定 commit 的同步任务。 */
    public boolean acceptPush(String projectId, String eventId, String before, String after) {
        requireEnabled(projectId);
        GitLabGitClient.validateSha(after);
        if (before != null && !before.isBlank() && !isZeroSha(before)) {
            GitLabGitClient.validateSha(before);
        }
        if (!store.recordWebhookEvent(projectId, eventId)) {
            return false;
        }
        enqueue(projectId, after.toLowerCase(Locale.ROOT));
        return true;
    }

    private void enqueue(String projectId, String requestedSha) {
        ProjectQueue queue = queues.computeIfAbsent(projectId, ignored -> new ProjectQueue());
        SyncRequest request = new SyncRequest(requestedSha);
        synchronized (queue) {
            queue.requests.addLast(request);
            if (queue.worker != null && !queue.worker.isDone()) {
                return;
            }
            try {
                queue.worker = executor.submit(() -> drain(projectId, queue));
            } catch (RuntimeException exception) {
                queue.requests.removeLastOccurrence(request);
                if (queue.requests.isEmpty()) {
                    queues.remove(projectId, queue);
                }
                throw exception;
            }
        }
    }

    private void drain(String projectId, ProjectQueue queue) {
        while (!Thread.currentThread().isInterrupted()) {
            SyncRequest request;
            synchronized (queue) {
                request = queue.requests.pollFirst();
                if (request == null) {
                    queue.worker = null;
                    return;
                }
            }
            synchronize(projectId, request.requestedSha());
        }
    }

    private void synchronize(String projectId, String requestedSha) {
        try {
            GitLabManagedProject project = requireEnabled(projectId);
            String accessToken = cipher.decrypt(project.encryptedAccessToken());
            if (!store.updateStateIfEnabled(projectId, GitLabProjectStatus.CLONING,
                    null, requestedSha, null)) {
                return;
            }
            gitClient.ensureRepository(project, accessToken);
            if (!store.updateStateIfEnabled(projectId, GitLabProjectStatus.SYNCING,
                    null, requestedSha, null)) {
                return;
            }
            gitClient.fetch(project, accessToken);
            String remoteHead = gitClient.remoteHead(project);
            String target = requestedSha == null ? remoteHead : requestedSha;
            if (!store.updateStateIfEnabled(projectId, GitLabProjectStatus.SYNCING,
                    null, target, null)) {
                return;
            }
            if (!target.equals(remoteHead) && !gitClient.isAncestor(projectId, target, remoteHead)) {
                throw new IllegalStateException("Webhook commit 不属于当前跟踪分支");
            }
            String previous = project.lastIndexedSha();
            if (previous != null && previous.equals(target)) {
                store.updateStateIfEnabled(projectId, GitLabProjectStatus.READY,
                        target, target, null);
                return;
            }
            if (previous != null && !gitClient.isAncestor(projectId, previous, target)) {
                throw new IllegalStateException("检测到非快进推送，已拒绝覆盖现有索引");
            }
            gitClient.checkout(projectId, target);
            if (!store.updateStateIfEnabled(projectId, GitLabProjectStatus.INDEXING,
                    null, target, null)) {
                return;
            }
            if (previous == null) {
                codeKnowledgeService.index(projectId);
            } else {
                incrementalIndexService.indexWithResult(projectId, previous, target);
            }
            store.updateStateIfEnabled(projectId, GitLabProjectStatus.READY,
                    target, target, null);
            log.info("GitLab project sync completed project={} commit={}", projectId, target);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(projectId, "同步任务被中断");
        } catch (Exception exception) {
            fail(projectId, publicError(exception));
            log.warn("GitLab project sync failed project={} exceptionType={}",
                    projectId, exception.getClass().getSimpleName());
        }
    }

    private void fail(String projectId, String message) {
        try {
            store.updateStateIfEnabled(projectId, GitLabProjectStatus.FAILED,
                    null, null, message);
        } catch (RuntimeException exception) {
            log.error("Unable to persist GitLab sync failure project={}", projectId);
        }
    }

    private String publicError(Exception exception) {
        if (exception instanceof IllegalArgumentException || exception instanceof IllegalStateException) {
            String message = exception.getMessage();
            if (message != null && (message.startsWith("Git ")
                    || message.startsWith("检测到") || message.startsWith("Webhook")
                    || message.startsWith("本地仓库"))) {
                return message;
            }
        }
        return "项目同步或索引失败，请检查 GitLab 凭据、分支和依赖服务";
    }

    private void restoreRegistry() {
        for (GitLabManagedProject project : store.all()) {
            if (project.status() == GitLabProjectStatus.DISABLED) {
                continue;
            }
            try {
                projectRegistry.registerDynamic(project.toProjectConfig());
            } catch (IllegalArgumentException exception) {
                store.updateState(project.projectId(), GitLabProjectStatus.FAILED, null,
                        null, "动态项目与静态配置冲突");
                log.warn("Skipped GitLab managed project due to registry conflict project={}",
                        project.projectId());
            }
        }
    }

    private void validate(CreateProject request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        GitLabGitClient.validateProjectId(request.projectId());
        GitLabGitClient.validateCloneUrl(request.cloneUrl());
        GitLabGitClient.validateBranch(text(request.branch(), "main"));
        if (request.gitPath() == null || request.gitPath().isBlank()
                || request.gitPath().startsWith("/") || request.gitPath().contains("..")) {
            throw new IllegalArgumentException("gitPath 必须是 GitLab path_with_namespace");
        }
        validateCollection(text(request.requirementCollection(), request.projectId() + "_requirements"));
        validateCollection(text(request.codeCollection(), request.projectId() + "_code"));
        if (request.accessToken() == null || request.accessToken().isBlank()
                || request.webhookSecret() == null || request.webhookSecret().isBlank()) {
            throw new IllegalArgumentException("accessToken 和 webhookSecret 不能为空");
        }
    }

    private void validateCollection(String collection) {
        if (!COLLECTION.matcher(collection).matches()) {
            throw new IllegalArgumentException("collection 名仅允许 1-128 位字母、数字、下划线和连字符");
        }
    }

    private GitLabManagedProject requireProject(String projectId) {
        GitLabGitClient.validateProjectId(projectId);
        return store.find(projectId)
                .orElseThrow(() -> new IllegalArgumentException("未知 GitLab 项目: " + projectId));
    }

    private GitLabManagedProject requireEnabled(String projectId) {
        GitLabManagedProject project = requireProject(projectId);
        if (project.status() == GitLabProjectStatus.DISABLED) {
            throw new IllegalArgumentException("GitLab 项目已禁用");
        }
        return project;
    }

    private boolean isZeroSha(String sha) {
        return "0".repeat(40).equals(sha);
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @PreDestroy
    void shutdown() {
        queues.values().forEach(queue -> {
            synchronized (queue) {
                queue.requests.clear();
                if (queue.worker != null) {
                    queue.worker.cancel(true);
                }
            }
        });
        queues.clear();
        executor.shutdownNow();
    }

    private record SyncRequest(String requestedSha) {
    }

    private static final class ProjectQueue {
        private final ArrayDeque<SyncRequest> requests = new ArrayDeque<>();
        private Future<?> worker;
    }

    /** 管理 API 的项目创建输入。 */
    public record CreateProject(
            String projectId,
            String name,
            String group,
            String side,
            String cloneUrl,
            String branch,
            String gitPath,
            String requirementCollection,
            String codeCollection,
            String accessToken,
            String webhookSecret
    ) {
    }
}
