package com.example.requirementrag.integration.gitlab;

import com.example.requirementrag.project.BusinessProject;
import com.example.requirementrag.project.BusinessProjectCatalogService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitLabProjectImportServiceTest {

    @Test
    void assignsTheWholeBatchToAnExistingBusinessProject() {
        GitLabAccountService accounts = mock(GitLabAccountService.class);
        GitLabApiClient api = mock(GitLabApiClient.class);
        GitLabGitClient git = mock(GitLabGitClient.class);
        GitLabProjectStore projectStore = mock(GitLabProjectStore.class);
        GitLabSyncService sync = mock(GitLabSyncService.class);
        BusinessProjectCatalogService catalog = mock(BusinessProjectCatalogService.class);
        GitLabProjectImportService service =
                new GitLabProjectImportService(accounts, api, git, projectStore, sync, catalog);
        GitLabConnection connection = new GitLabConnection(
                "connection-a", "GitLab", "https://gitlab.example.com",
                "gitlab.example.com", "user", "User", "ciphertext",
                GitLabConnectionStatus.ACTIVE, "now", null, "now", "now");
        GitLabApiClient.RemoteProject remote = new GitLabApiClient.RemoteProject(
                11, "Orders", "group/orders",
                "https://gitlab.example.com/group/orders.git", "main",
                "private", false, "2026-08-18T00:00:00Z", membership());
        BusinessProject businessProject = new BusinessProject(
                "immortal", "Immortal", "immortal-game-service", "requirements",
                "fengshen", "immortal-game-service", "immortal-game-service", "5.1",
                BusinessProject.Status.ACTIVE, "now", "now");
        when(catalog.requireImportTarget("immortal")).thenReturn(businessProject);
        when(accounts.activeConnection("connection-a")).thenReturn(connection);
        when(accounts.accessToken(connection)).thenReturn("glpat-secret");
        when(api.project(connection.baseUrl(), "glpat-secret", 11)).thenReturn(remote);
        when(api.branches(connection.baseUrl(), "glpat-secret", 11)).thenReturn(List.of(branch("main")));
        when(git.repositoryPath("group-orders")).thenReturn(java.nio.file.Path.of("/tmp/group-orders"));
        when(sync.registerConnected(any())).thenReturn(new GitLabManagedProject(
                "group-orders", "Orders", "group", "server", remote.httpUrlToRepo(),
                "main", remote.pathWithNamespace(), "unlinked", "group_orders_code",
                "/tmp/group-orders", "connection-a", 11L, "", "secret",
                GitLabProjectStatus.PENDING, null, null, null, "now", "now").toView());

        GitLabProjectImportService.BatchImportResponse response = service.importProjects(
                "connection-a", new GitLabProjectImportService.BatchImportRequest(
                        "immortal", List.of(item(11, "group-orders"))));

        assertThat(response.accepted()).isEqualTo(1);
        verify(catalog).registerOwnedRepository("immortal", "group-orders", "Orders",
                "server", "group_orders_code", "/tmp/group-orders", "group/orders");
    }

    @Test
    void importsMembershipProjectsIndependentlyAndReturnsOneTimeWebhookSecret() {
        GitLabAccountService accounts = mock(GitLabAccountService.class);
        GitLabApiClient api = mock(GitLabApiClient.class);
        GitLabGitClient git = mock(GitLabGitClient.class);
        GitLabProjectStore projectStore = mock(GitLabProjectStore.class);
        GitLabSyncService sync = mock(GitLabSyncService.class);
        GitLabProjectImportService service =
                new GitLabProjectImportService(accounts, api, git, projectStore, sync);
        GitLabConnection connection = new GitLabConnection(
                "connection-a", "GitLab", "https://gitlab.example.com",
                "gitlab.example.com", "user", "User", "ciphertext",
                GitLabConnectionStatus.ACTIVE, "now", null, "now", "now");
        GitLabApiClient.RemoteProject remote = new GitLabApiClient.RemoteProject(
                11, "Orders", "group/orders",
                "https://gitlab.example.com/group/orders.git", "main",
                "private", false, "2026-08-18T00:00:00Z", membership());
        GitLabManagedProject.View created = new GitLabManagedProject(
                "group-orders", "Orders", "group", "server", remote.httpUrlToRepo(),
                "main", remote.pathWithNamespace(), "group_orders_requirements",
                "group_orders_code", "/tmp/group-orders", "connection-a", "",
                "encrypted-webhook", GitLabProjectStatus.PENDING,
                null, null, null, "now", "now").toView();
        when(accounts.activeConnection("connection-a")).thenReturn(connection);
        when(accounts.accessToken(connection)).thenReturn("glpat-secret");
        when(api.project(connection.baseUrl(), "glpat-secret", 11)).thenReturn(remote);
        when(api.branches(connection.baseUrl(), "glpat-secret", 11))
                .thenReturn(List.of(branch("main")));
        when(api.project(connection.baseUrl(), "glpat-secret", 12))
                .thenReturn(new GitLabApiClient.RemoteProject(
                        12, "Missing", "public/missing",
                        "https://gitlab.example.com/public/missing.git", "main",
                        "public", false, "2026-08-18T00:00:00Z", null));
        when(sync.registerConnected(any())).thenReturn(created);

        GitLabProjectImportService.BatchImportResponse response = service.importProjects(
                "connection-a",
                new GitLabProjectImportService.BatchImportRequest(List.of(
                        new GitLabProjectImportService.ImportProject(
                                11, "group-orders", "server", "main", "group_orders_code"),
                        new GitLabProjectImportService.ImportProject(
                                12, "group-missing", "server", "main", "group_missing_code"))));

        assertThat(response.accepted()).isEqualTo(1);
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.results().get(0)).satisfies(result -> {
            assertThat(result.status()).isEqualTo("ACCEPTED");
            assertThat(result.webhookSecret()).hasSizeGreaterThanOrEqualTo(16);
            assertThat(result.webhookPath()).isEqualTo("/api/webhooks/gitlab/group-orders");
        });
        assertThat(response.results().get(1)).satisfies(result -> {
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.errorCode()).isEqualTo("GITLAB_PROJECT_NOT_MEMBER");
        });
        verify(sync).registerConnected(any(GitLabSyncService.CreateConnectedProject.class));
        verify(git, org.mockito.Mockito.never()).validateRemote(any(), any(), any());
    }

    @Test
    void rejectsArchivedProjectWithoutAffectingOtherBatchResults() {
        GitLabAccountService accounts = mock(GitLabAccountService.class);
        GitLabApiClient api = mock(GitLabApiClient.class);
        GitLabGitClient git = mock(GitLabGitClient.class);
        GitLabProjectStore projectStore = mock(GitLabProjectStore.class);
        GitLabSyncService sync = mock(GitLabSyncService.class);
        GitLabProjectImportService service =
                new GitLabProjectImportService(accounts, api, git, projectStore, sync);
        GitLabConnection connection = new GitLabConnection(
                "connection-a", "GitLab", "https://gitlab.example.com",
                "gitlab.example.com", "user", "User", "ciphertext",
                GitLabConnectionStatus.ACTIVE, "now", null, "now", "now");
        GitLabApiClient.RemoteProject archived = new GitLabApiClient.RemoteProject(
                13, "Archive", "group/archive",
                "https://gitlab.example.com/group/archive.git", "main",
                "private", true, "2026-08-18T00:00:00Z", membership());
        when(accounts.activeConnection("connection-a")).thenReturn(connection);
        when(accounts.accessToken(connection)).thenReturn("glpat-secret");
        when(api.project(connection.baseUrl(), "glpat-secret", 13)).thenReturn(archived);

        GitLabProjectImportService.BatchImportResponse response = service.importProjects(
                "connection-a",
                new GitLabProjectImportService.BatchImportRequest(List.of(
                        new GitLabProjectImportService.ImportProject(
                                13, "group-archive", "server", "main", "group_archive_code"))));

        assertThat(response.accepted()).isZero();
        assertThat(response.results()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.errorCode()).isEqualTo("GITLAB_PROJECT_ARCHIVED");
        });
    }

    @Test
    void rejectsAProjectThatWasAlreadyImportedUnderAnotherNexusId() {
        GitLabAccountService accounts = mock(GitLabAccountService.class);
        GitLabApiClient api = mock(GitLabApiClient.class);
        GitLabGitClient git = mock(GitLabGitClient.class);
        GitLabProjectStore projectStore = mock(GitLabProjectStore.class);
        GitLabSyncService sync = mock(GitLabSyncService.class);
        GitLabProjectImportService service =
                new GitLabProjectImportService(accounts, api, git, projectStore, sync);
        GitLabConnection connection = new GitLabConnection(
                "connection-a", "GitLab", "https://gitlab.example.com",
                "gitlab.example.com", "user", "User", "ciphertext",
                GitLabConnectionStatus.ACTIVE, "now", null, "now", "now");
        GitLabApiClient.RemoteProject remote = new GitLabApiClient.RemoteProject(
                15, "Orders", "group/orders",
                "https://gitlab.example.com/group/orders.git", "main",
                "private", false, "2026-08-18T00:00:00Z", membership());
        GitLabManagedProject existing = new GitLabManagedProject(
                "orders-existing", "Orders", "group", "server", remote.httpUrlToRepo(),
                "main", remote.pathWithNamespace(), "orders_requirements", "orders_code",
                "/tmp/orders", "encrypted-pat", "encrypted-webhook",
                GitLabProjectStatus.READY, null, null, null, "now", "now");
        when(accounts.activeConnection("connection-a")).thenReturn(connection);
        when(accounts.accessToken(connection)).thenReturn("glpat-secret");
        when(api.project(connection.baseUrl(), "glpat-secret", 15)).thenReturn(remote);
        when(api.branches(connection.baseUrl(), "glpat-secret", 15))
                .thenReturn(List.of(branch("main")));
        when(projectStore.findByRemoteProject("connection-a", 15)).thenReturn(Optional.of(existing));

        GitLabProjectImportService.BatchImportResponse response = service.importProjects(
                "connection-a",
                new GitLabProjectImportService.BatchImportRequest(List.of(
                        new GitLabProjectImportService.ImportProject(
                                15, "group-orders", "server", "main", "group_orders_code"))));

        assertThat(response.results()).singleElement().satisfies(result ->
                assertThat(result.errorCode()).isEqualTo("GITLAB_PROJECT_ALREADY_IMPORTED"));
    }

    @Test
    void resolvesBatchProjectDetailsConcurrentlyWithoutWaitingForGitValidation() throws Exception {
        GitLabAccountService accounts = mock(GitLabAccountService.class);
        GitLabApiClient api = mock(GitLabApiClient.class);
        GitLabGitClient git = mock(GitLabGitClient.class);
        GitLabProjectStore projectStore = mock(GitLabProjectStore.class);
        GitLabSyncService sync = mock(GitLabSyncService.class);
        GitLabProjectImportService service =
                new GitLabProjectImportService(accounts, api, git, projectStore, sync);
        GitLabConnection connection = new GitLabConnection(
                "connection-a", "GitLab", "https://gitlab.example.com",
                "gitlab.example.com", "user", "User", "ciphertext",
                GitLabConnectionStatus.ACTIVE, "now", null, "now", "now");
        when(accounts.activeConnection("connection-a")).thenReturn(connection);
        when(accounts.accessToken(connection)).thenReturn("glpat-secret");
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(api.project(org.mockito.ArgumentMatchers.eq(connection.baseUrl()),
                org.mockito.ArgumentMatchers.eq("glpat-secret"),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> {
                    long id = invocation.getArgument(2);
                    entered.countDown();
                    if (!entered.await(1, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("项目详情未并发读取");
                    }
                    release.await(1, TimeUnit.SECONDS);
                    return new GitLabApiClient.RemoteProject(
                            id, "P" + id, "group/p" + id,
                            "https://gitlab.example.com/group/p" + id + ".git", "main",
                            "private", false, "2026-08-18T00:00:00Z", membership());
                });
        when(api.branches(org.mockito.ArgumentMatchers.eq(connection.baseUrl()),
                org.mockito.ArgumentMatchers.eq("glpat-secret"),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> List.of(branch("main")));
        when(sync.registerConnected(any())).thenAnswer(invocation -> {
            GitLabSyncService.CreateConnectedProject request = invocation.getArgument(0);
            return new GitLabManagedProject(
                    request.projectId(), request.name(), request.group(), request.side(),
                    request.cloneUrl(), request.branch(), request.gitPath(),
                    GitLabManagedProject.unlinkedRequirementCollection(request.projectId()),
                    request.codeCollection(),
                    "/tmp/" + request.projectId(), request.connectionId(), request.remoteProjectId(),
                    "", "encrypted-webhook", GitLabProjectStatus.PENDING,
                    null, null, null, "now", "now").toView();
        });

        Thread releaser = Thread.ofVirtual().start(() -> {
            try {
                entered.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                release.countDown();
            }
        });
        GitLabProjectImportService.BatchImportResponse response = service.importProjects(
                "connection-a",
                new GitLabProjectImportService.BatchImportRequest(List.of(
                        item(21, "project-21"), item(22, "project-22"))));
        releaser.join();

        assertThat(response.accepted()).isEqualTo(2);
        verify(git, org.mockito.Mockito.never()).validateRemote(any(), any(), any());
    }

    @Test
    void rejectsPublicProjectWithoutAccountMembership() {
        GitLabAccountService accounts = mock(GitLabAccountService.class);
        GitLabApiClient api = mock(GitLabApiClient.class);
        GitLabGitClient git = mock(GitLabGitClient.class);
        GitLabProjectStore projectStore = mock(GitLabProjectStore.class);
        GitLabSyncService sync = mock(GitLabSyncService.class);
        GitLabProjectImportService service =
                new GitLabProjectImportService(accounts, api, git, projectStore, sync);
        GitLabConnection connection = new GitLabConnection(
                "connection-a", "GitLab", "https://gitlab.example.com",
                "gitlab.example.com", "user", "User", "ciphertext",
                GitLabConnectionStatus.ACTIVE, "now", null, "now", "now");
        when(accounts.activeConnection("connection-a")).thenReturn(connection);
        when(accounts.accessToken(connection)).thenReturn("glpat-secret");
        when(api.project(connection.baseUrl(), "glpat-secret", 17))
                .thenReturn(new GitLabApiClient.RemoteProject(
                        17, "Public", "other/public",
                        "https://gitlab.example.com/other/public.git", "main",
                        "public", false, "2026-08-18T00:00:00Z", null));

        GitLabProjectImportService.BatchImportResponse response = service.importProjects(
                "connection-a",
                new GitLabProjectImportService.BatchImportRequest(List.of(item(17, "public"))));

        assertThat(response.results()).singleElement().satisfies(result ->
                assertThat(result.errorCode()).isEqualTo("GITLAB_PROJECT_NOT_MEMBER"));
    }

    @Test
    void rejectsBranchThatNoLongerExistsBeforeRegisteringProject() {
        GitLabAccountService accounts = mock(GitLabAccountService.class);
        GitLabApiClient api = mock(GitLabApiClient.class);
        GitLabGitClient git = mock(GitLabGitClient.class);
        GitLabProjectStore projectStore = mock(GitLabProjectStore.class);
        GitLabSyncService sync = mock(GitLabSyncService.class);
        GitLabProjectImportService service =
                new GitLabProjectImportService(accounts, api, git, projectStore, sync);
        GitLabConnection connection = new GitLabConnection(
                "connection-a", "GitLab", "https://gitlab.example.com",
                "gitlab.example.com", "user", "User", "ciphertext",
                GitLabConnectionStatus.ACTIVE, "now", null, "now", "now");
        GitLabApiClient.RemoteProject remote = new GitLabApiClient.RemoteProject(
                18, "Orders", "group/orders",
                "https://gitlab.example.com/group/orders.git", "main",
                "private", false, "2026-08-18T00:00:00Z", membership());
        when(accounts.activeConnection("connection-a")).thenReturn(connection);
        when(accounts.accessToken(connection)).thenReturn("glpat-secret");
        when(api.project(connection.baseUrl(), "glpat-secret", 18)).thenReturn(remote);
        when(api.branches(connection.baseUrl(), "glpat-secret", 18))
                .thenReturn(List.of(branch("main")));

        GitLabProjectImportService.BatchImportResponse response = service.importProjects(
                "connection-a",
                new GitLabProjectImportService.BatchImportRequest(List.of(
                        new GitLabProjectImportService.ImportProject(
                                18, "group-orders", "server", "deleted", "group_orders_code"))));

        assertThat(response.results()).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.errorCode()).isEqualTo("GITLAB_BRANCH_NOT_FOUND");
        });
        verify(sync, org.mockito.Mockito.never()).registerConnected(any());
    }

    private GitLabProjectImportService.ImportProject item(long remoteId, String projectId) {
        return new GitLabProjectImportService.ImportProject(
                remoteId, projectId, "server", "main",
                projectId.replace('-', '_') + "_code");
    }

    private GitLabApiClient.Permissions membership() {
        return new GitLabApiClient.Permissions(
                new GitLabApiClient.ProjectAccess(30), null);
    }

    private GitLabApiClient.RemoteBranch branch(String name) {
        return new GitLabApiClient.RemoteBranch(name, "main".equals(name),
                false, false, "a".repeat(40));
    }
}
