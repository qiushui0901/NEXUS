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
        String encryptedAccessToken,
        String encryptedWebhookSecret,
        GitLabProjectStatus status,
        String lastIndexedSha,
        String targetSha,
        String lastError,
        String createdAt,
        String updatedAt
) {
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
        return new View(projectId, name, group, side, cloneUrl, branch, gitPath,
                requirementCollection, codeCollection, status, lastIndexedSha, targetSha,
                lastError, createdAt, updatedAt);
    }

    public record View(
            String projectId,
            String name,
            String group,
            String side,
            String cloneUrl,
            String branch,
            String gitPath,
            String requirementCollection,
            String codeCollection,
            GitLabProjectStatus status,
            String lastIndexedSha,
            String targetSha,
            String lastError,
            String createdAt,
            String updatedAt
    ) {
    }
}
