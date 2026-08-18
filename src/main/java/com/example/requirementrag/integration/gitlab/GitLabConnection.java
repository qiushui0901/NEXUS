package com.example.requirementrag.integration.gitlab;

/** GitLab 账号连接。PAT 只以 AES-GCM 密文持久化。 */
public record GitLabConnection(
        String id,
        String name,
        String baseUrl,
        String host,
        String username,
        String displayName,
        String encryptedAccessToken,
        GitLabConnectionStatus status,
        String lastVerifiedAt,
        String lastError,
        String createdAt,
        String updatedAt
) {
    public View toView() {
        return new View(id, name, baseUrl, host, username, displayName, status,
                lastVerifiedAt, lastError, createdAt, updatedAt);
    }

    /** 不包含凭据的管理 API 视图。 */
    public record View(
            String id,
            String name,
            String baseUrl,
            String host,
            String username,
            String displayName,
            GitLabConnectionStatus status,
            String lastVerifiedAt,
            String lastError,
            String createdAt,
            String updatedAt
    ) {
    }
}
