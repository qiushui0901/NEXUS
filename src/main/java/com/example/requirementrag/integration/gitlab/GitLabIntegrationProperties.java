package com.example.requirementrag.integration.gitlab;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.IDN;
import java.util.List;
import java.util.Locale;

/** GitLab 自动接入配置。默认关闭，开启后才初始化凭据、数据库和 Git 同步组件。 */
@ConfigurationProperties("app.rag.gitlab")
public record GitLabIntegrationProperties(
        boolean enabled,
        String repositoryRootPath,
        String databasePath,
        String encryptionKey,
        int gitTimeoutSeconds,
        int syncThreads,
        List<String> allowedHosts,
        boolean allowPrivateHosts,
        boolean uiEnabled
) {
    public GitLabIntegrationProperties(boolean enabled,
                                       String repositoryRootPath,
                                       String databasePath,
                                       String encryptionKey,
                                       int gitTimeoutSeconds,
                                       int syncThreads) {
        this(enabled, repositoryRootPath, databasePath, encryptionKey,
                gitTimeoutSeconds, syncThreads, List.of("gitlab.com"), false, true);
    }

    public GitLabIntegrationProperties(boolean enabled,
                                       String repositoryRootPath,
                                       String databasePath,
                                       String encryptionKey,
                                       int gitTimeoutSeconds,
                                       int syncThreads,
                                       List<String> allowedHosts,
                                       boolean allowPrivateHosts) {
        this(enabled, repositoryRootPath, databasePath, encryptionKey,
                gitTimeoutSeconds, syncThreads, allowedHosts, allowPrivateHosts, true);
    }

    @ConstructorBinding
    public GitLabIntegrationProperties {
        repositoryRootPath = text(repositoryRootPath, "data/gitlab-repositories");
        databasePath = text(databasePath, "data/gitlab-integration.db");
        encryptionKey = encryptionKey == null ? "" : encryptionKey.trim();
        gitTimeoutSeconds = gitTimeoutSeconds <= 0 ? 120 : gitTimeoutSeconds;
        syncThreads = syncThreads <= 0 ? 2 : Math.min(syncThreads, 8);
        List<String> configuredHosts = allowedHosts == null || allowedHosts.isEmpty()
                ? List.of("gitlab.com") : allowedHosts;
        try {
            allowedHosts = configuredHosts.stream()
                    .map(String::trim)
                    .filter(host -> !host.isEmpty())
                    .map(GitLabIntegrationProperties::normalizeHost)
                    .map(host -> host.toLowerCase(Locale.ROOT))
                    .distinct()
                    .toList();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("GitLab allowedHosts 包含无效主机名", exception);
        }
        if (allowedHosts.isEmpty()) {
            throw new IllegalArgumentException("GitLab allowedHosts 不能为空");
        }
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String normalizeHost(String host) {
        String unwrapped = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        return unwrapped.contains(":") ? unwrapped : IDN.toASCII(unwrapped);
    }
}
