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
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
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
    private final GitLabCredentialResolver credentialResolver;
    private final GitLabGitClient gitClient;
    private final ProjectRegistry projectRegistry;
    private final CodeKnowledgeService codeKnowledgeService;
    private final IncrementalCodeIndexService incrementalIndexService;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, ProjectQueue> queues = new ConcurrentHashMap<>();

    @Autowired
    public GitLabSyncService(GitLabProjectStore store,
                             GitLabCredentialCipher cipher,
                             GitLabCredentialResolver credentialResolver,
                             GitLabGitClient gitClient,
                             ProjectRegistry projectRegistry,
                             CodeKnowledgeService codeKnowledgeService,
                             IncrementalCodeIndexService incrementalIndexService,
                             GitLabIntegrationProperties properties) {
        this(store, cipher, credentialResolver, gitClient, projectRegistry,
                codeKnowledgeService, incrementalIndexService,
                Executors.newFixedThreadPool(properties.syncThreads(), Thread.ofVirtual()
                        .name("gitlab-sync-", 0).factory()));
    }

    GitLabSyncService(GitLabProjectStore store,
                      GitLabCredentialCipher cipher,
                      GitLabCredentialResolver credentialResolver,
                      GitLabGitClient gitClient,
                      ProjectRegistry projectRegistry,
                      CodeKnowledgeService codeKnowledgeService,
                      IncrementalCodeIndexService incrementalIndexService,
                      ExecutorService executor) {
        this.store = store;
        this.cipher = cipher;
        this.credentialResolver = credentialResolver;
        this.gitClient = gitClient;
        this.projectRegistry = projectRegistry;
        this.codeKnowledgeService = codeKnowledgeService;
        this.incrementalIndexService = incrementalIndexService;
        this.executor = executor;
        restoreRegistry();
    }

    GitLabSyncService(GitLabProjectStore store,
                      GitLabCredentialCipher cipher,
                      GitLabGitClient gitClient,
                      ProjectRegistry projectRegistry,
                      CodeKnowledgeService codeKnowledgeService,
                      IncrementalCodeIndexService incrementalIndexService,
                      ExecutorService executor) {
        this(store, cipher, new GitLabCredentialResolver(cipher), gitClient, projectRegistry,
                codeKnowledgeService, incrementalIndexService, executor);
    }

    /** 保存项目、发布到动态注册表并提交首次同步任务。 */
    public GitLabManagedProject.View register(CreateProject request) {
        validate(request);
        return registerProject(
                request.projectId(), request.name(), request.group(), request.side(),
                request.cloneUrl(), request.branch(), request.gitPath(),
                text(request.requirementCollection(), request.projectId() + "_requirements"),
                request.codeCollection(),
                null, null, cipher.encrypt(request.accessToken()), request.webhookSecret());
    }

    /** 保存账号连接下的项目并提交首次同步，项目记录不再复制 PAT 密文。 */
    public GitLabManagedProject.View registerConnected(CreateConnectedProject request) {
        validateConnected(request);
        return registerProject(
                request.projectId(), request.name(), request.group(), request.side(),
                request.cloneUrl(), request.branch(), request.gitPath(),
                GitLabManagedProject.unlinkedRequirementCollection(request.projectId()),
                request.codeCollection(),
                request.connectionId(), request.remoteProjectId(), "", request.webhookSecret());
    }

    private GitLabManagedProject.View registerProject(
            String projectId, String name, String group, String side,
            String cloneUrl, String branch, String gitPath,
            String requirementCollection, String codeCollection,
            String connectionId, Long remoteProjectId,
            String encryptedAccessToken, String webhookSecret) {
        if (projectRegistry.isStaticProject(projectId)) {
            throw new IllegalArgumentException("projectId 与静态项目冲突");
        }
        if (store.find(projectId).isPresent()) {
            throw new IllegalArgumentException("GitLab 项目已接入: " + projectId);
        }
        String now = Instant.now().toString();
        GitLabManagedProject project = new GitLabManagedProject(
                projectId,
                text(name, projectId),
                text(group, "default"),
                text(side, "server"),
                cloneUrl,
                text(branch, "main"),
                gitPath,
                requirementCollection,
                text(codeCollection, projectId + "_code"),
                gitClient.repositoryPath(projectId).toString(),
                connectionId,
                remoteProjectId,
                encryptedAccessToken,
                cipher.encrypt(webhookSecret),
                GitLabProjectStatus.PENDING,
                null, null, null, now, now);
        if (!store.insert(project)) {
            throw new IllegalArgumentException("GitLab projectId 或远端项目已经接入");
        }
        boolean registered = false;
        try {
            registered = projectRegistry.registerDynamic(project.toProjectConfig());
            enqueue(project.projectId(), null, "INITIAL");
            return require(project.projectId());
        } catch (RuntimeException exception) {
            if (registered) {
                projectRegistry.unregisterDynamic(project.projectId());
            }
            store.delete(project.projectId());
            throw exception;
        }
    }

    public List<GitLabManagedProject.View> list() {
        return store.all().stream().map(this::view).toList();
    }

    public GitLabManagedProject.View require(String projectId) {
        return view(requireProject(projectId));
    }

    /** 手动同步配置分支的远端 HEAD。 */
    public GitLabManagedProject.View sync(String projectId) {
        GitLabManagedProject project = requireEnabled(projectId);
        enqueue(project.projectId(), null, "MANUAL");
        return require(projectId);
    }

    /** 仅允许失败任务重试，避免把 retry 当作第二套同步语义。 */
    public GitLabManagedProject.View retry(String projectId) {
        GitLabManagedProject project = requireEnabled(projectId);
        if (project.status() != GitLabProjectStatus.FAILED) {
            throw new IllegalArgumentException("只有 FAILED 项目可以重试");
        }
        enqueue(project.projectId(), project.targetSha(), "RETRY");
        return require(projectId);
    }

    /** 禁用动态项目，停止新任务并保留仓库、索引和元数据。 */
    public GitLabManagedProject.View disable(String projectId) {
        requireProject(projectId);
        store.updateStateKeepingTarget(projectId, GitLabProjectStatus.DISABLED, null, null);
        ProjectQueue queue = queues.remove(projectId);
        List<SyncRequest> queued = List.of();
        if (queue != null) {
            synchronized (queue) {
                queued = List.copyOf(queue.requests);
                queue.requests.clear();
                if (queue.worker != null) {
                    queue.worker.cancel(true);
                }
            }
        }
        queued.forEach(request -> cancelDisabledJob(request.jobId(), request.requestedSha()));
        projectRegistry.unregisterDynamic(projectId);
        return require(projectId);
    }

    /** 原地恢复已停用项目，保留历史与索引并提交最新 HEAD 同步。 */
    public GitLabManagedProject.View enable(String projectId) {
        GitLabManagedProject project = requireProject(projectId);
        if (project.status() != GitLabProjectStatus.DISABLED) {
            throw new IllegalArgumentException("只有已停用项目可以重新启用");
        }
        credentialResolver.resolve(project);
        if (!store.enableIfDisabled(projectId)) {
            throw new IllegalArgumentException("GitLab 项目状态已变化，请刷新后重试");
        }
        boolean registered = false;
        try {
            registered = projectRegistry.registerDynamic(project.toProjectConfig());
            enqueue(projectId, null, "REENABLE");
            return require(projectId);
        } catch (RuntimeException exception) {
            if (registered) {
                projectRegistry.unregisterDynamic(projectId);
            }
            store.updateState(projectId, GitLabProjectStatus.DISABLED,
                    null, project.targetSha(), "重新启用失败");
            throw exception;
        }
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
        enqueue(projectId, after.toLowerCase(Locale.ROOT), "WEBHOOK");
        return true;
    }

    public GitLabGitClient.ValidationResult validateConnection(ValidateConnection request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        return gitClient.validateRemote(request.cloneUrl(), text(request.branch(), "main"),
                request.accessToken());
    }

    public ValidationResponse validateProject(ValidateProject request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        GitLabGitClient.validateProjectId(request.projectId());
        if (projectRegistry.isStaticProject(request.projectId())) {
            throw new IllegalArgumentException("projectId 与静态项目冲突");
        }
        if (store.find(request.projectId()).isPresent()) {
            throw new IllegalArgumentException("GitLab 项目已接入: " + request.projectId());
        }
        validateGitPath(request.gitPath());
        return new ValidationResponse(true, "项目标识可用");
    }

    public ValidationResponse validateConfig(ValidateConfig request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        validateCollection(request.requirementCollection());
        validateCollection(request.codeCollection());
        if (request.webhookSecret() != null) {
            validateWebhookSecret(request.webhookSecret());
        }
        return new ValidationResponse(true, "配置校验通过");
    }

    public List<GitLabSyncJob> jobs(String projectId) {
        requireProject(projectId);
        return store.jobs(projectId);
    }

    public GitLabSyncJob job(String projectId, String jobId) {
        requireProject(projectId);
        return store.findJob(projectId, jobId)
                .orElseThrow(() -> new IllegalArgumentException("未知 GitLab 同步任务: " + jobId));
    }

    public GitLabWebhookStatus webhookStatus(String projectId) {
        requireProject(projectId);
        return store.webhookStatus(projectId).orElse(new GitLabWebhookStatus(
                projectId, "NEVER_RECEIVED", null, null, "尚未收到 Webhook", null));
    }

    public RotatedSecret rotateWebhookSecret(String projectId) {
        requireEnabled(projectId);
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        store.updateWebhookSecret(projectId, cipher.encrypt(secret));
        return new RotatedSecret(secret, Instant.now().toString());
    }

    public void recordWebhookStatus(String projectId, String status, String eventId,
                                    String targetSha, String message) {
        store.recordWebhookStatus(projectId, status, eventId, targetSha, message);
    }

    private void enqueue(String projectId, String requestedSha, String triggerType) {
        ProjectQueue queue = queues.computeIfAbsent(projectId, ignored -> new ProjectQueue());
        GitLabManagedProject project = requireProject(projectId);
        String jobId = store.createJob(projectId, triggerType, project.lastIndexedSha(), requestedSha);
        SyncRequest request = new SyncRequest(jobId, requestedSha);
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
                store.updateJob(jobId, "FAILED", "QUEUE", requestedSha,
                        "QUEUE_SUBMISSION_FAILED", "无法提交同步任务", true);
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
            synchronize(projectId, request);
        }
    }

    private void synchronize(String projectId, SyncRequest request) {
        String jobId = request.jobId();
        String requestedSha = request.requestedSha();
        try {
            GitLabManagedProject project = requireEnabled(projectId);
            String accessToken = credentialResolver.resolve(project).accessToken();
            store.updateJob(jobId, "RUNNING", "CLONE", requestedSha, null, null, false);
            if (!store.updateStateIfEnabled(projectId, GitLabProjectStatus.CLONING,
                    null, requestedSha, null)) {
                cancelDisabledJob(jobId, requestedSha);
                return;
            }
            gitClient.ensureRepository(project, accessToken);
            store.updateJob(jobId, "RUNNING", "FETCH", requestedSha, null, null, false);
            if (!store.updateStateIfEnabled(projectId, GitLabProjectStatus.SYNCING,
                    null, requestedSha, null)) {
                cancelDisabledJob(jobId, requestedSha);
                return;
            }
            gitClient.fetch(project, accessToken);
            String remoteHead = gitClient.remoteHead(project);
            String target = requestedSha == null ? remoteHead : requestedSha;
            store.updateJob(jobId, "RUNNING", "RESOLVE_TARGET", target, null, null, false);
            if (!store.updateStateIfEnabled(projectId, GitLabProjectStatus.SYNCING,
                    null, target, null)) {
                cancelDisabledJob(jobId, target);
                return;
            }
            if (!target.equals(remoteHead) && !gitClient.isAncestor(projectId, target, remoteHead)) {
                throw new IllegalStateException("Webhook commit 不属于当前跟踪分支");
            }
            String previous = project.lastIndexedSha();
            if (previous != null && previous.equals(target)) {
                if (!store.updateStateIfEnabled(projectId, GitLabProjectStatus.READY,
                        target, target, null)) {
                    cancelDisabledJob(jobId, target);
                    return;
                }
                store.updateJob(jobId, "SUCCEEDED", "PUBLISH", target, null,
                        "目标版本已是最新索引", true);
                return;
            }
            if (previous != null && !gitClient.isAncestor(projectId, previous, target)) {
                throw new IllegalStateException("检测到非快进推送，已拒绝覆盖现有索引");
            }
            gitClient.checkout(projectId, target);
            store.updateJob(jobId, "RUNNING", "INDEX", target, null, null, false);
            if (!store.updateStateIfEnabled(projectId, GitLabProjectStatus.INDEXING,
                    null, target, null)) {
                cancelDisabledJob(jobId, target);
                return;
            }
            if (previous == null) {
                codeKnowledgeService.index(projectId);
            } else {
                incrementalIndexService.indexWithResult(projectId, previous, target);
            }
            if (!store.updateStateIfEnabled(projectId, GitLabProjectStatus.READY,
                    target, target, null)) {
                cancelDisabledJob(jobId, target);
                return;
            }
            store.updateJob(jobId, "SUCCEEDED", "PUBLISH", target, null,
                    "索引发布完成", true);
            log.info("GitLab project sync completed project={} commit={}", projectId, target);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (isDisabled(projectId)) {
                cancelDisabledJob(jobId, requestedSha);
            } else {
                fail(projectId, jobId, "SYNC_INTERRUPTED", "同步任务被中断");
            }
        } catch (Exception exception) {
            if (isDisabled(projectId)) {
                cancelDisabledJob(jobId, requestedSha);
            } else {
                fail(projectId, jobId, errorCode(exception), publicError(exception));
                log.warn("GitLab project sync failed project={} exceptionType={}",
                        projectId, exception.getClass().getSimpleName());
            }
        }
    }

    private void cancelDisabledJob(String jobId, String targetSha) {
        store.updateJob(jobId, "CANCELLED", "DISABLED", targetSha,
                "PROJECT_DISABLED", "项目已停用", true);
    }

    private boolean isDisabled(String projectId) {
        return store.find(projectId)
                .map(project -> project.status() == GitLabProjectStatus.DISABLED)
                .orElse(false);
    }

    private void fail(String projectId, String jobId, String code, String message) {
        try {
            store.updateStateIfEnabledKeepingTarget(projectId, GitLabProjectStatus.FAILED,
                    null, message);
            store.updateJob(jobId, "FAILED", "FAILED", null, code, message, true);
        } catch (RuntimeException exception) {
            log.error("Unable to persist GitLab sync failure project={}", projectId);
        }
    }

    private String publicError(Exception exception) {
        if (exception instanceof GitLabApiException) {
            return exception.getMessage();
        }
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

    private String errorCode(Exception exception) {
        if (exception instanceof GitLabApiException apiException) {
            return apiException.code();
        }
        if (exception.getMessage() != null && exception.getMessage().contains("非快进")) {
            return "NON_FAST_FORWARD";
        }
        if (exception.getMessage() != null && exception.getMessage().startsWith("Git ")) {
            return "GIT_OPERATION_FAILED";
        }
        return "GITLAB_SYNC_FAILED";
    }

    private void restoreRegistry() {
        for (GitLabManagedProject project : store.all()) {
            if (project.status() == GitLabProjectStatus.DISABLED) {
                continue;
            }
            try {
                projectRegistry.registerDynamic(project.toProjectConfig());
            } catch (IllegalArgumentException exception) {
                store.updateStateKeepingTarget(project.projectId(), GitLabProjectStatus.FAILED,
                        null, "动态项目与静态配置冲突");
                log.warn("Skipped GitLab managed project due to registry conflict project={}",
                        project.projectId());
                continue;
            }
            if (isInterruptedStatus(project.status())) {
                try {
                    enqueue(project.projectId(), project.targetSha(), "RECOVERY");
                } catch (RuntimeException exception) {
                    store.updateStateKeepingTarget(project.projectId(), GitLabProjectStatus.FAILED,
                            null, "应用启动时无法恢复中断的同步任务");
                    log.warn("Unable to restore interrupted GitLab sync project={} exceptionType={}",
                            project.projectId(), exception.getClass().getSimpleName());
                }
            }
        }
    }

    private boolean isInterruptedStatus(GitLabProjectStatus status) {
        return status == GitLabProjectStatus.PENDING
                || status == GitLabProjectStatus.CLONING
                || status == GitLabProjectStatus.SYNCING
                || status == GitLabProjectStatus.INDEXING;
    }

    private void validate(CreateProject request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        GitLabGitClient.validateProjectId(request.projectId());
        gitClient.validateCloneUrl(request.cloneUrl());
        GitLabGitClient.validateBranch(text(request.branch(), "main"));
        validateGitPath(request.gitPath());
        validateCollection(text(request.requirementCollection(), request.projectId() + "_requirements"));
        validateCollection(text(request.codeCollection(), request.projectId() + "_code"));
        if (request.accessToken() == null || request.accessToken().isBlank()) {
            throw new IllegalArgumentException("accessToken 不能为空");
        }
        validateWebhookSecret(request.webhookSecret());
    }

    private void validateConnected(CreateConnectedProject request) {
        if (request == null) throw new IllegalArgumentException("请求不能为空");
        if (request.connectionId() == null || request.connectionId().isBlank()) {
            throw new IllegalArgumentException("connectionId 不能为空");
        }
        if (request.remoteProjectId() <= 0) {
            throw new IllegalArgumentException("remoteProjectId 必须为正数");
        }
        GitLabGitClient.validateProjectId(request.projectId());
        gitClient.validateCloneUrl(request.cloneUrl());
        GitLabGitClient.validateBranch(text(request.branch(), "main"));
        validateGitPath(request.gitPath());
        validateCollection(text(request.codeCollection(), request.projectId() + "_code"));
        validateWebhookSecret(request.webhookSecret());
    }

    private void validateCollection(String collection) {
        if (!COLLECTION.matcher(collection).matches()) {
            throw new IllegalArgumentException("collection 名仅允许 1-128 位字母、数字、下划线和连字符");
        }
    }

    private void validateGitPath(String gitPath) {
        if (gitPath == null || gitPath.isBlank() || gitPath.startsWith("/")
                || gitPath.contains("..") || !gitPath.contains("/")) {
            throw new IllegalArgumentException("gitPath 必须是 GitLab path_with_namespace");
        }
    }

    private void validateWebhookSecret(String secret) {
        if (secret == null || secret.length() < 16 || secret.length() > 256) {
            throw new IllegalArgumentException("webhookSecret 必须为 16-256 位");
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

    private GitLabManagedProject.View view(GitLabManagedProject project) {
        GitLabSyncJob latest = store.latestJob(project.projectId()).orElse(null);
        GitLabWebhookStatus webhook = store.webhookStatus(project.projectId()).orElse(null);
        boolean active = latest != null && ("QUEUED".equals(latest.status())
                || "RUNNING".equals(latest.status()));
        return new GitLabManagedProject.View(
                project.projectId(), project.name(), project.group(), project.side(),
                project.cloneUrl(), project.branch(), project.gitPath(), project.connectionId(),
                project.connectionId() == null || project.connectionId().isBlank()
                        ? project.requirementCollection() : null,
                project.codeCollection(), project.status(),
                project.lastIndexedSha(), project.targetSha(), project.lastError(),
                project.createdAt(), project.updatedAt(),
                project.status() != GitLabProjectStatus.DISABLED,
                project.lastIndexedSha() != null,
                project.targetSha() != null && !project.targetSha().equals(project.lastIndexedSha()),
                store.lastSuccessfulSyncAt(project.projectId()),
                webhook == null ? null : webhook.receivedAt(),
                active ? latest.id() : null,
                active ? latest.phase() : null,
                project.status() == GitLabProjectStatus.FAILED
                        ? (latest == null ? "GITLAB_SYNC_FAILED" : latest.errorCode()) : null,
                project.lastError());
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

    private record SyncRequest(String jobId, String requestedSha) {
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

    public record CreateConnectedProject(
            String connectionId,
            long remoteProjectId,
            String projectId,
            String name,
            String group,
            String side,
            String cloneUrl,
            String branch,
            String gitPath,
            String codeCollection,
            String webhookSecret
    ) {
    }

    public record ValidateConnection(String cloneUrl, String branch, String accessToken) {
    }

    public record ValidateProject(String projectId, String gitPath) {
    }

    public record ValidateConfig(
            String requirementCollection,
            String codeCollection,
            String webhookSecret
    ) {
    }

    public record ValidationResponse(boolean valid, String message) {
    }

    public record RotatedSecret(String webhookSecret, String rotatedAt) {
    }
}
