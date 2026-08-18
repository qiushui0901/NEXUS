package com.example.requirementrag.integration.gitlab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 新项目从账号连接读取 PAT，旧项目兼容读取项目级密文。 */
@Component
@ConditionalOnProperty(name = "app.rag.gitlab.enabled", havingValue = "true")
public class GitLabCredentialResolver {
    private final GitLabConnectionStore connectionStore;
    private final GitLabCredentialCipher cipher;
    private final GitLabHostPolicy hostPolicy;

    @Autowired
    public GitLabCredentialResolver(GitLabConnectionStore connectionStore,
                                    GitLabCredentialCipher cipher,
                                    GitLabHostPolicy hostPolicy) {
        this.connectionStore = connectionStore;
        this.cipher = cipher;
        this.hostPolicy = hostPolicy;
    }

    GitLabCredentialResolver(GitLabCredentialCipher cipher) {
        this(null, cipher, null);
    }

    GitLabCredentialResolver(GitLabConnectionStore connectionStore,
                             GitLabCredentialCipher cipher) {
        this(connectionStore, cipher, null);
    }

    public String accessToken(GitLabManagedProject project) {
        return resolve(project).accessToken();
    }

    public ResolvedCredential resolve(GitLabManagedProject project) {
        if (project.connectionId() == null || project.connectionId().isBlank()) {
            return new ResolvedCredential(cipher.decrypt(project.encryptedAccessToken()), null);
        }
        if (connectionStore == null) {
            throw new GitLabApiException("GITLAB_CONNECTION_UNAVAILABLE",
                    "GitLab 账号连接不可用，请重新关联");
        }
        GitLabConnection connection = connectionStore.find(project.connectionId())
                .orElseThrow(() -> new GitLabApiException("GITLAB_CONNECTION_UNAVAILABLE",
                        "GitLab 账号连接不存在，请重新关联"));
        if (connection.status() == GitLabConnectionStatus.DISABLED) {
            throw new GitLabApiException("GITLAB_CONNECTION_DISABLED", "GitLab 账号连接已停用");
        }
        if (connection.status() != GitLabConnectionStatus.ACTIVE) {
            throw new GitLabApiException("GITLAB_CONNECTION_INVALID",
                    "GitLab 账号连接已失效，请重新授权");
        }
        if (hostPolicy != null) {
            hostPolicy.validateCloneUrlForBaseUrl(project.cloneUrl(), connection.baseUrl());
        }
        return new ResolvedCredential(cipher.decrypt(connection.encryptedAccessToken()),
                connection.baseUrl());
    }

    public record ResolvedCredential(String accessToken, String baseUrl) {
    }
}
