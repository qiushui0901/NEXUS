package com.example.requirementrag.integration.gitlab;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** GitLab 自动接入配置。默认关闭，开启后才初始化凭据、数据库和 Git 同步组件。 */
@ConfigurationProperties("app.rag.gitlab")
public record GitLabIntegrationProperties(
        boolean enabled,
        String repositoryRootPath,
        String databasePath,
        String encryptionKey,
        int gitTimeoutSeconds,
        int syncThreads
) {
    @ConstructorBinding
    public GitLabIntegrationProperties {
        repositoryRootPath = text(repositoryRootPath, "data/gitlab-repositories");
        databasePath = text(databasePath, "data/gitlab-integration.db");
        encryptionKey = encryptionKey == null ? "" : encryptionKey.trim();
        gitTimeoutSeconds = gitTimeoutSeconds <= 0 ? 120 : gitTimeoutSeconds;
        syncThreads = syncThreads <= 0 ? 2 : Math.min(syncThreads, 8);
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
