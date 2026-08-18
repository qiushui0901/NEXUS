package com.example.requirementrag.integration.gitlab;

import com.example.requirementrag.config.ProjectRegistry;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitLabAccountServiceTest {

    @Test
    void createsEncryptedConnectionAndProjectsMembershipWithImportStates() throws Exception {
        Fixture fixture = fixture();
        when(fixture.apiClient.account("https://gitlab.example.com", "glpat-secret"))
                .thenReturn(new GitLabApiClient.Account(7, "qiushui", "秋水"));

        GitLabConnection.View created = fixture.service.create(
                new GitLabAccountService.CreateConnection(
                        "公司 GitLab", "https://gitlab.example.com/", "glpat-secret"));
        GitLabConnection stored = fixture.connectionStore.find(created.id()).orElseThrow();

        assertThat(created.username()).isEqualTo("qiushui");
        assertThat(created.toString()).doesNotContain("glpat-secret");
        assertThat(stored.encryptedAccessToken()).isNotEqualTo("glpat-secret");
        assertThat(fixture.cipher.decrypt(stored.encryptedAccessToken())).isEqualTo("glpat-secret");

        String now = Instant.now().toString();
        fixture.projectStore.save(new GitLabManagedProject(
                "group-imported", "Imported", "group", "server",
                "https://gitlab.example.com/group/imported.git", "main", "group/imported",
                "group_imported_requirements", "group_imported_code", "/tmp/imported",
                created.id(), "", "encrypted-webhook", GitLabProjectStatus.READY,
                "a".repeat(40), "a".repeat(40), null, now, now));
        when(fixture.apiClient.membershipProjects(
                "https://gitlab.example.com", "glpat-secret", null))
                .thenReturn(List.of(
                        remote(11, "Available", "group/available", "main", false),
                        remote(12, "Imported", "group/imported", "main", false),
                        remote(13, "Archived", "group/archived", "main", true),
                        remote(14, "Empty", "group/empty", null, false)));

        GitLabAccountService.ProjectPage page = fixture.service.projects(
                created.id(), 0, 50, null);

        assertThat(page.total()).isEqualTo(4);
        assertThat(page.items()).extracting(
                        GitLabAccountService.RemoteProjectView::pathWithNamespace,
                        GitLabAccountService.RemoteProjectView::importState)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(
                                "group/available", GitLabAccountService.ImportState.AVAILABLE),
                        org.assertj.core.groups.Tuple.tuple(
                                "group/imported", GitLabAccountService.ImportState.IMPORTED),
                        org.assertj.core.groups.Tuple.tuple(
                                "group/archived", GitLabAccountService.ImportState.ARCHIVED),
                        org.assertj.core.groups.Tuple.tuple(
                                "group/empty", GitLabAccountService.ImportState.NO_DEFAULT_BRANCH));
    }

    @Test
    void marksExistingConnectionInvalidWhenTokenVerificationFails() throws Exception {
        Fixture fixture = fixture();
        when(fixture.apiClient.account("https://gitlab.example.com", "glpat-secret"))
                .thenReturn(new GitLabApiClient.Account(7, "qiushui", "秋水"));
        GitLabConnection.View created = fixture.service.create(
                new GitLabAccountService.CreateConnection(
                        "公司 GitLab", "https://gitlab.example.com", "glpat-secret"));
        when(fixture.apiClient.account("https://gitlab.example.com", "glpat-secret"))
                .thenThrow(new GitLabApiException("GITLAB_TOKEN_INVALID", "Token 已失效"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> fixture.service.verify(created.id()))
                .isInstanceOf(GitLabApiException.class);

        assertThat(fixture.connectionStore.find(created.id())).get()
                .extracting(GitLabConnection::status)
                .isEqualTo(GitLabConnectionStatus.INVALID);
    }

    @Test
    void keepsConnectionActiveWhenVerificationFailsTransiently() throws Exception {
        Fixture fixture = fixture();
        when(fixture.apiClient.account("https://gitlab.example.com", "glpat-secret"))
                .thenReturn(new GitLabApiClient.Account(7, "qiushui", "秋水"));
        GitLabConnection.View created = fixture.service.create(
                new GitLabAccountService.CreateConnection(
                        "公司 GitLab", "https://gitlab.example.com", "glpat-secret"));
        when(fixture.apiClient.account("https://gitlab.example.com", "glpat-secret"))
                .thenThrow(new GitLabApiException("GITLAB_API_UNAVAILABLE", "GitLab API 暂时不可用"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> fixture.service.verify(created.id()))
                .isInstanceOf(GitLabApiException.class);

        assertThat(fixture.connectionStore.find(created.id())).get()
                .satisfies(connection -> {
                    assertThat(connection.status()).isEqualTo(GitLabConnectionStatus.ACTIVE);
                    assertThat(connection.lastError()).isNull();
                });
    }

    @Test
    void appendsRemoteIdWhenGeneratedProjectIdAlreadyExists() throws Exception {
        Fixture fixture = fixture();
        when(fixture.apiClient.account("https://gitlab.example.com", "glpat-secret"))
                .thenReturn(new GitLabApiClient.Account(7, "qiushui", "秋水"));
        GitLabConnection.View created = fixture.service.create(
                new GitLabAccountService.CreateConnection(
                        "公司 GitLab", "https://gitlab.example.com", "glpat-secret"));
        String now = Instant.now().toString();
        fixture.projectStore.save(new GitLabManagedProject(
                "group-orders", "Other", "other", "server",
                "https://gitlab.example.com/other/repository.git", "main", "other/repository",
                "other_requirements", "other_code", "/tmp/other",
                "encrypted-pat", "encrypted-webhook", GitLabProjectStatus.READY,
                null, null, null, now, now));
        when(fixture.apiClient.membershipProjects(
                "https://gitlab.example.com", "glpat-secret", null))
                .thenReturn(List.of(remote(11, "Orders", "group/orders", "main", false)));

        GitLabAccountService.ProjectPage page = fixture.service.projects(created.id(), 0, 50, null);

        assertThat(page.items()).singleElement()
                .extracting(GitLabAccountService.RemoteProjectView::projectId)
                .isEqualTo("group-orders-11");
    }

    @Test
    void identifiesImportedProjectByConnectionAndRemoteIdAfterRename() throws Exception {
        Fixture fixture = fixture();
        when(fixture.apiClient.account("https://gitlab.example.com", "glpat-secret"))
                .thenReturn(new GitLabApiClient.Account(7, "qiushui", "秋水"));
        GitLabConnection.View created = fixture.service.create(
                new GitLabAccountService.CreateConnection(
                        "公司 GitLab", "https://gitlab.example.com", "glpat-secret"));
        String now = Instant.now().toString();
        fixture.projectStore.save(new GitLabManagedProject(
                "orders", "Orders", "group", "server",
                "https://gitlab.example.com/group/old-orders.git", "main", "group/old-orders",
                "orders_requirements", "orders_code", "/tmp/orders",
                created.id(), 99L, "", "encrypted-webhook", GitLabProjectStatus.READY,
                null, null, null, now, now));
        when(fixture.apiClient.membershipProjects(
                "https://gitlab.example.com", "glpat-secret", "renamed"))
                .thenReturn(List.of(remote(99, "Renamed", "new-group/renamed", "main", false)));

        GitLabAccountService.ProjectPage page =
                fixture.service.projects(created.id(), 0, 50, "renamed");

        assertThat(page.items()).singleElement().satisfies(project -> {
            assertThat(project.importState()).isEqualTo(GitLabAccountService.ImportState.IMPORTED);
            assertThat(project.importedProjectId()).isEqualTo("orders");
        });
    }

    private GitLabApiClient.RemoteProject remote(long id, String name, String path,
                                                  String branch, boolean archived) {
        return new GitLabApiClient.RemoteProject(
                id, name, path, "https://gitlab.example.com/" + path + ".git",
                branch, "private", archived, "2026-08-18T00:00:00Z");
    }

    private Fixture fixture() throws Exception {
        String root = Files.createTempDirectory("nexus-gitlab-account-service-").toString();
        GitLabIntegrationProperties properties = new GitLabIntegrationProperties(
                true, root + "/repos", root + "/gitlab.db", key(), 10, 1,
                List.of("gitlab.example.com"), false);
        GitLabHostPolicy policy = new GitLabHostPolicy(properties,
                ignored -> new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})});
        GitLabConnectionStore connections = new GitLabConnectionStore(properties);
        GitLabProjectStore projects = new GitLabProjectStore(properties);
        GitLabCredentialCipher cipher = new GitLabCredentialCipher(properties);
        GitLabApiClient api = mock(GitLabApiClient.class);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        GitLabAccountService service = new GitLabAccountService(
                connections, projects, cipher, api, policy, registry);
        return new Fixture(connections, projects, cipher, api, service);
    }

    private String key() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    private record Fixture(
            GitLabConnectionStore connectionStore,
            GitLabProjectStore projectStore,
            GitLabCredentialCipher cipher,
            GitLabApiClient apiClient,
            GitLabAccountService service
    ) {
    }
}
