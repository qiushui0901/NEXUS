package com.example.requirementrag.integration.gitlab;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitLabCredentialResolverTest {

    @Test
    void resolvesConnectedAndLegacyProjectCredentials() throws Exception {
        String root = Files.createTempDirectory("nexus-gitlab-credential-resolver-").toString();
        GitLabIntegrationProperties properties = new GitLabIntegrationProperties(
                true, root + "/repos", root + "/gitlab.db", key(), 10, 1);
        GitLabCredentialCipher cipher = new GitLabCredentialCipher(properties);
        GitLabConnectionStore store = new GitLabConnectionStore(properties);
        String now = Instant.now().toString();
        store.save(new GitLabConnection(
                "connection-a", "GitLab", "https://gitlab.example.com",
                "gitlab.example.com", "user", "User", cipher.encrypt("account-token"),
                GitLabConnectionStatus.ACTIVE, now, null, now, now));
        GitLabCredentialResolver resolver = new GitLabCredentialResolver(store, cipher);

        assertThat(resolver.accessToken(project("connection-a", ""))).isEqualTo("account-token");
        assertThat(resolver.accessToken(project(null, cipher.encrypt("legacy-token"))))
                .isEqualTo("legacy-token");

        store.updateStatus("connection-a", GitLabConnectionStatus.INVALID, "失效");
        assertThatThrownBy(() -> resolver.accessToken(project("connection-a", "")))
                .isInstanceOf(GitLabApiException.class)
                .extracting(error -> ((GitLabApiException) error).code())
                .isEqualTo("GITLAB_CONNECTION_INVALID");
    }

    @Test
    void refusesToResolveAccountTokenForAnotherAllowedGitLabHost() throws Exception {
        String root = Files.createTempDirectory("nexus-gitlab-credential-scope-").toString();
        GitLabIntegrationProperties properties = new GitLabIntegrationProperties(
                true, root + "/repos", root + "/gitlab.db", key(), 10, 1,
                List.of("gitlab-a.example.com", "gitlab-b.example.com"), false);
        GitLabCredentialCipher cipher = new GitLabCredentialCipher(properties);
        GitLabConnectionStore store = new GitLabConnectionStore(properties);
        String now = Instant.now().toString();
        store.save(new GitLabConnection(
                "connection-a", "GitLab A", "https://gitlab-a.example.com",
                "gitlab-a.example.com", "user", "User", cipher.encrypt("account-token"),
                GitLabConnectionStatus.ACTIVE, now, null, now, now));
        GitLabHostPolicy policy = new GitLabHostPolicy(properties,
                ignored -> new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})});
        GitLabCredentialResolver resolver = new GitLabCredentialResolver(store, cipher, policy);
        GitLabManagedProject project = new GitLabManagedProject(
                "project-a", "Project A", "group", "server",
                "https://gitlab-b.example.com/group/project-a.git", "main", "group/project-a",
                "project_a_requirements", "project_a_code", "/tmp/project-a",
                "connection-a", 11L, "", "encrypted-webhook", GitLabProjectStatus.READY,
                null, null, null, now, now);

        assertThatThrownBy(() -> resolver.accessToken(project))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主机或端口不一致");
    }

    private GitLabManagedProject project(String connectionId, String encryptedToken) {
        return new GitLabManagedProject(
                "project-a", "Project A", "group", "server",
                "https://gitlab.example.com/group/project-a.git", "main", "group/project-a",
                "project_a_requirements", "project_a_code", "/tmp/project-a",
                connectionId, encryptedToken, "encrypted-webhook", GitLabProjectStatus.READY,
                null, null, null, "now", "now");
    }

    private String key() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }
}
