package com.example.requirementrag.integration.gitlab;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GitLabConnectionStoreTest {

    @Test
    void persistsConnectionsWithoutExposingOrLosingCiphertext() throws Exception {
        String database = Files.createTempDirectory("nexus-gitlab-connections-")
                .resolve("connections.db").toString();
        GitLabIntegrationProperties properties = new GitLabIntegrationProperties(
                true, null, database, "", 10, 1);
        GitLabConnectionStore store = new GitLabConnectionStore(properties);
        String now = Instant.now().toString();
        store.save(new GitLabConnection(
                "connection-a", "公司 GitLab", "https://gitlab.example.com",
                "gitlab.example.com", "qiushui", "秋水", "encrypted-pat",
                GitLabConnectionStatus.ACTIVE, now, null, now, now));

        GitLabConnectionStore reopened = new GitLabConnectionStore(properties);
        assertThat(reopened.find("connection-a")).get().satisfies(connection -> {
            assertThat(connection.name()).isEqualTo("公司 GitLab");
            assertThat(connection.username()).isEqualTo("qiushui");
            assertThat(connection.encryptedAccessToken()).isEqualTo("encrypted-pat");
            assertThat(connection.toView().toString()).doesNotContain("encrypted-pat");
        });

        reopened.updateStatus("connection-a", GitLabConnectionStatus.INVALID, "Token 已失效");

        assertThat(reopened.find("connection-a")).get().satisfies(connection -> {
            assertThat(connection.status()).isEqualTo(GitLabConnectionStatus.INVALID);
            assertThat(connection.lastError()).isEqualTo("Token 已失效");
            assertThat(connection.lastVerifiedAt()).isNotBlank();
        });
    }
}
