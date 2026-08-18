package com.example.requirementrag.integration.gitlab;

import com.example.requirementrag.config.RagProperties;

import java.util.List;

/** GitLab 自动接入项目的持久化模型。敏感字段只保存 AES-GCM 密文。 */
public record GitLabManagedProject(
        String projectId,
        String name,
        String group,
        String side,
        String cloneUrl,
        String branch,
        String gitPath,
        String requirementCollection,
        String codeCollection,
        String repositoryPath,
        String connectionId,
        Long remoteProjectId,
        String encryptedAccessToken,
        String encryptedWebhookSecret,
        GitLabProjectStatus status,
        String lastIndexedSha,
        String targetSha,
        String lastError,
        String createdAt,
        String updatedAt
) {
    /** 旧项目与聚焦测试使用的兼容构造器。 */
    public GitLabManagedProject(
            String projectId, String name, String group, String side, String cloneUrl,
            String branch, String gitPath, String requirementCollection, String codeCollection,
            String repositoryPath, String encryptedAccessToken, String encryptedWebhookSecret,
            GitLabProjectStatus status, String lastIndexedSha, String targetSha, String lastError,
            String createdAt, String updatedAt
    ) {
        this(projectId, name, group, side, cloneUrl, branch, gitPath, requirementCollection,
                codeCollection, repositoryPath, null, null, encryptedAccessToken, encryptedWebhookSecret,
                status, lastIndexedSha, targetSha, lastError, createdAt, updatedAt);
    }

    /** 账号连接项目在增加远端稳定 ID 前的兼容构造器。 */
    public GitLabManagedProject(
            String projectId, String name, String group, String side, String cloneUrl,
            String branch, String gitPath, String requirementCollection, String codeCollection,
            String repositoryPath, String connectionId, String encryptedAccessToken,
            String encryptedWebhookSecret, GitLabProjectStatus status, String lastIndexedSha,
            String targetSha, String lastError, String createdAt, String updatedAt
    ) {
        this(projectId, name, group, side, cloneUrl, branch, gitPath, requirementCollection,
                codeCollection, repositoryPath, connectionId, null, encryptedAccessToken,
                encryptedWebhookSecret, status, lastIndexedSha, targetSha, lastError, createdAt, updatedAt);
    }

    /** 转换为现有索引和检索链路使用的项目配置。 */
    public RagProperties.ProjectConfig toProjectConfig() {
        return new RagProperties.ProjectConfig(
                projectId, name, group, side, requirementCollection, codeCollection,
                repositoryPath, gitPath,
                new RagProperties.ProjectKnowledge(false, null, null, null, null, null, null, 800),
                List.of(),
                List.of("/target/", "/.git/", "/node_modules/"),
                1_000_000);
    }

    /** API 响应中使用的无敏感信息视图。 */
    public View toView() {
        return new View(projectId, name, group, side, cloneUrl, branch, gitPath, connectionId,
                requirementCollection, codeCollection, status, lastIndexedSha, targetSha,
                lastError, createdAt, updatedAt, status != GitLabProjectStatus.DISABLED,
                lastIndexedSha != null, targetSha != null && !targetSha.equals(lastIndexedSha),
                null, null, null, null, status == GitLabProjectStatus.FAILED
                ? "GITLAB_SYNC_FAILED" : null, lastError);
    }

    public record View(
            String projectId,
            String name,
            String group,
            String side,
            String cloneUrl,
            String branch,
            String gitPath,
            String connectionId,
            String requirementCollection,
            String codeCollection,
            GitLabProjectStatus status,
            String lastIndexedSha,
            String targetSha,
            String lastError,
            String createdAt,
            String updatedAt,
            boolean syncAvailable,
            boolean indexAvailable,
            boolean revisionDrift,
            String lastSuccessfulSyncAt,
            String lastWebhookAt,
            String activeJobId,
            String activePhase,
            String errorCode,
            String errorMessage
    ) {
    }
}
