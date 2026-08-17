package com.example.requirementrag.integration.gitlab;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.code.IncrementalCodeIndexService;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitLabSyncServiceTest {

    @Test
    void registersClonesAndRunsInitialFullIndex() throws Exception {
        Fixture fixture = fixture();
        String target = "a".repeat(40);
        when(fixture.gitClient.remoteHead(org.mockito.ArgumentMatchers.any())).thenReturn(target);

        fixture.service.register(new GitLabSyncService.CreateProject(
                "project-a", "Project A", "group", "server",
                "https://gitlab.example.com/group/project-a.git", "main", "group/project-a",
                null, null, "glpat-secret", "webhook-secret"));

        GitLabManagedProject saved = fixture.store.find("project-a").orElseThrow();
        assertThat(saved.status()).isEqualTo(GitLabProjectStatus.READY);
        assertThat(saved.lastIndexedSha()).isEqualTo(target);
        assertThat(fixture.registry.find("project-a")).isPresent();
        verify(fixture.gitClient).ensureRepository(
                org.mockito.ArgumentMatchers.any(GitLabManagedProject.class),
                org.mockito.ArgumentMatchers.eq("glpat-secret"));
        verify(fixture.codeKnowledgeService).index("project-a");
        fixture.close();
    }

    @Test
    void usesIncrementalIndexForFastForwardUpdate() throws Exception {
        Fixture fixture = fixture();
        String oldSha = "a".repeat(40);
        String newSha = "b".repeat(40);
        GitLabManagedProject existing = project(fixture, oldSha);
        fixture.store.save(existing);
        fixture.registry.registerDynamic(existing.toProjectConfig());
        when(fixture.gitClient.remoteHead(existing)).thenReturn(newSha);
        when(fixture.gitClient.isAncestor("project-a", oldSha, newSha)).thenReturn(true);

        fixture.service.sync("project-a");

        assertThat(fixture.store.find("project-a")).get()
                .extracting(GitLabManagedProject::status, GitLabManagedProject::lastIndexedSha)
                .containsExactly(GitLabProjectStatus.READY, newSha);
        verify(fixture.incrementalIndexService).indexWithResult("project-a", oldSha, newSha);
        fixture.close();
    }

    @Test
    void rejectsNonFastForwardUpdateWithoutReplacingCurrentIndex() throws Exception {
        Fixture fixture = fixture();
        String oldSha = "a".repeat(40);
        String rewrittenSha = "b".repeat(40);
        GitLabManagedProject existing = project(fixture, oldSha);
        fixture.store.save(existing);
        fixture.registry.registerDynamic(existing.toProjectConfig());
        when(fixture.gitClient.remoteHead(existing)).thenReturn(rewrittenSha);
        when(fixture.gitClient.isAncestor("project-a", oldSha, rewrittenSha)).thenReturn(false);

        fixture.service.sync("project-a");

        assertThat(fixture.store.find("project-a")).get().satisfies(saved -> {
            assertThat(saved.status()).isEqualTo(GitLabProjectStatus.FAILED);
            assertThat(saved.lastIndexedSha()).isEqualTo(oldSha);
            assertThat(saved.targetSha()).isEqualTo(rewrittenSha);
            assertThat(saved.lastError()).contains("非快进");
        });
        verify(fixture.incrementalIndexService, never())
                .indexWithResult(any(), any(), any());
        fixture.close();
    }

    @Test
    void retryUsesRecordedFailedTargetInsteadOfMovingRemoteHead() throws Exception {
        Fixture fixture = fixture();
        String oldSha = "a".repeat(40);
        String failedTarget = "b".repeat(40);
        String newerRemoteHead = "c".repeat(40);
        GitLabManagedProject existing = project(fixture, oldSha);
        fixture.store.save(new GitLabManagedProject(
                existing.projectId(), existing.name(), existing.group(), existing.side(),
                existing.cloneUrl(), existing.branch(), existing.gitPath(),
                existing.requirementCollection(), existing.codeCollection(), existing.repositoryPath(),
                existing.encryptedAccessToken(), existing.encryptedWebhookSecret(),
                GitLabProjectStatus.FAILED, oldSha, failedTarget, "上次同步失败",
                existing.createdAt(), existing.updatedAt()));
        fixture.registry.registerDynamic(existing.toProjectConfig());
        when(fixture.gitClient.remoteHead(any())).thenReturn(newerRemoteHead);
        when(fixture.gitClient.isAncestor("project-a", failedTarget, newerRemoteHead)).thenReturn(true);
        when(fixture.gitClient.isAncestor("project-a", oldSha, failedTarget)).thenReturn(true);

        fixture.service.retry("project-a");

        assertThat(fixture.store.find("project-a")).get().satisfies(saved -> {
            assertThat(saved.status()).isEqualTo(GitLabProjectStatus.READY);
            assertThat(saved.lastIndexedSha()).isEqualTo(failedTarget);
            assertThat(saved.targetSha()).isEqualTo(failedTarget);
        });
        verify(fixture.incrementalIndexService)
                .indexWithResult("project-a", oldSha, failedTarget);
        fixture.close();
    }

    @Test
    void queuesPushReceivedWhileInitialIndexIsStillRunning() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Fixture fixture = fixture(executor);
        String initialSha = "a".repeat(40);
        String pushedSha = "b".repeat(40);
        CountDownLatch indexingStarted = new CountDownLatch(1);
        CountDownLatch releaseIndexing = new CountDownLatch(1);
        when(fixture.gitClient.remoteHead(any())).thenReturn(initialSha, pushedSha);
        when(fixture.gitClient.isAncestor("project-a", initialSha, pushedSha)).thenReturn(true);
        doAnswer(invocation -> {
            indexingStarted.countDown();
            assertThat(releaseIndexing.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(fixture.codeKnowledgeService).index("project-a");

        try {
            fixture.service.register(request());
            assertThat(indexingStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.service.acceptPush(
                    "project-a", "event-2", initialSha, pushedSha)).isTrue();
            assertThat(fixture.service.acceptPush(
                    "project-a", "event-2", initialSha, pushedSha)).isFalse();

            releaseIndexing.countDown();
            awaitReady(fixture.store, pushedSha);

            verify(fixture.incrementalIndexService)
                    .indexWithResult("project-a", initialSha, pushedSha);
        } finally {
            releaseIndexing.countDown();
            fixture.close();
        }
    }

    @Test
    void disableCannotBeOverwrittenByRunningBackgroundTask() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Fixture fixture = fixture(executor);
        CountDownLatch cloneStarted = new CountDownLatch(1);
        CountDownLatch releaseClone = new CountDownLatch(1);
        doAnswer(invocation -> {
            cloneStarted.countDown();
            releaseClone.await(5, TimeUnit.SECONDS);
            return null;
        }).when(fixture.gitClient).ensureRepository(any(), any());

        try {
            fixture.service.register(request());
            assertThat(cloneStarted.await(5, TimeUnit.SECONDS)).isTrue();

            fixture.service.disable("project-a");
            releaseClone.countDown();

            assertThat(fixture.store.find("project-a")).get()
                    .extracting(GitLabManagedProject::status)
                    .isEqualTo(GitLabProjectStatus.DISABLED);
            assertThat(fixture.registry.find("project-a")).isEmpty();
        } finally {
            releaseClone.countDown();
            fixture.close();
        }
    }

    private Fixture fixture() throws Exception {
        return fixture(new DirectExecutor());
    }

    private Fixture fixture(ExecutorService executor) throws Exception {
        String root = Files.createTempDirectory("nexus-gitlab-sync-").toString();
        GitLabIntegrationProperties properties = new GitLabIntegrationProperties(
                true, root + "/repos", root + "/projects.db", key(), 10, 1);
        GitLabProjectStore store = new GitLabProjectStore(properties);
        GitLabCredentialCipher cipher = new GitLabCredentialCipher(properties);
        GitLabGitClient gitClient = mock(GitLabGitClient.class);
        when(gitClient.repositoryPath("project-a")).thenReturn(Files.createDirectories(
                java.nio.file.Path.of(root, "repos", "project-a")));
        ProjectRegistry registry = registry();
        CodeKnowledgeService full = mock(CodeKnowledgeService.class);
        IncrementalCodeIndexService incremental = mock(IncrementalCodeIndexService.class);
        GitLabSyncService service = new GitLabSyncService(
                store, cipher, gitClient, registry, full, incremental, executor);
        return new Fixture(properties, store, cipher, gitClient, registry, full, incremental, executor, service);
    }

    private ProjectRegistry registry() {
        RagProperties properties = mock(RagProperties.class);
        RagProperties.ProjectConfig configured = new RagProperties.ProjectConfig(
                "static-project", "Static", "default", "server",
                "static_requirements", "static_code", "/tmp/static", "group/static",
                new RagProperties.ProjectKnowledge(false, null, null, null, null, null, null, 800),
                List.of(), List.of(), 1_000_000);
        when(properties.projects()).thenReturn(List.of(configured));
        return new ProjectRegistry(properties);
    }

    private GitLabManagedProject project(Fixture fixture, String sha) {
        String now = Instant.now().toString();
        return new GitLabManagedProject(
                "project-a", "Project A", "group", "server",
                "https://gitlab.example.com/group/project-a.git", "main", "group/project-a",
                "project_a_requirements", "project_a_code",
                fixture.gitClient.repositoryPath("project-a").toString(),
                fixture.cipher.encrypt("glpat-secret"), fixture.cipher.encrypt("webhook-secret"),
                GitLabProjectStatus.READY, sha, sha, null, now, now);
    }

    private GitLabSyncService.CreateProject request() {
        return new GitLabSyncService.CreateProject(
                "project-a", "Project A", "group", "server",
                "https://gitlab.example.com/group/project-a.git", "main", "group/project-a",
                null, null, "glpat-secret", "webhook-secret");
    }

    private void awaitReady(GitLabProjectStore store, String expectedSha) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            GitLabManagedProject project = store.find("project-a").orElseThrow();
            if (project.status() == GitLabProjectStatus.READY
                    && expectedSha.equals(project.lastIndexedSha())) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(store.find("project-a")).get().satisfies(project -> {
            assertThat(project.status()).isEqualTo(GitLabProjectStatus.READY);
            assertThat(project.lastIndexedSha()).isEqualTo(expectedSha);
        });
    }

    private String key() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    private record Fixture(
            GitLabIntegrationProperties properties,
            GitLabProjectStore store,
            GitLabCredentialCipher cipher,
            GitLabGitClient gitClient,
            ProjectRegistry registry,
            CodeKnowledgeService codeKnowledgeService,
            IncrementalCodeIndexService incrementalIndexService,
            ExecutorService executor,
            GitLabSyncService service
    ) {
        void close() {
            service.shutdown();
        }
    }

    private static final class DirectExecutor extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
