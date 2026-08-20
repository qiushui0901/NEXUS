package com.example.requirementrag.integration.gitlab;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GitLabProjectStoreTest {

    @Test
    void persistsProjectsStateAndWebhookDeduplicationAcrossInstances() throws Exception {
        String database = Files.createTempDirectory("nexus-gitlab-store-").resolve("projects.db").toString();
        GitLabIntegrationProperties properties = new GitLabIntegrationProperties(
                true, null, database, "", 10, 1);
        GitLabProjectStore store = new GitLabProjectStore(properties);
        GitLabManagedProject project = project();

        store.save(project);
        store.updateState("project-a", GitLabProjectStatus.READY,
                "a".repeat(40), "a".repeat(40), null);
        assertThat(store.recordWebhookEvent("project-a", "event-1")).isTrue();
        assertThat(store.recordWebhookEvent("project-a", "event-1")).isFalse();

        GitLabProjectStore reopened = new GitLabProjectStore(properties);
        assertThat(reopened.find("project-a")).get()
                .satisfies(saved -> {
                    assertThat(saved.status()).isEqualTo(GitLabProjectStatus.READY);
                    assertThat(saved.lastIndexedSha()).isEqualTo("a".repeat(40));
                    assertThat(saved.targetSha()).isEqualTo("a".repeat(40));
                    assertThat(saved.encryptedAccessToken()).isEqualTo("encrypted-pat");
                });
        assertThat(reopened.all()).hasSize(1);
        reopened.updateState("project-a", GitLabProjectStatus.CLONING, null, null, null);
        assertThat(reopened.find("project-a")).get()
                .extracting(GitLabManagedProject::targetSha)
                .isNull();
        reopened.updateState("project-a", GitLabProjectStatus.SYNCING,
                null, "b".repeat(40), null);
        reopened.updateStateKeepingTarget("project-a", GitLabProjectStatus.FAILED,
                null, "同步失败");
        assertThat(reopened.find("project-a")).get()
                .extracting(GitLabManagedProject::targetSha)
                .isEqualTo("b".repeat(40));
        reopened.updateStateKeepingTarget("project-a", GitLabProjectStatus.DISABLED, null, null);
        assertThat(reopened.updateStateIfEnabled(
                "project-a", GitLabProjectStatus.READY, "b".repeat(40), "b".repeat(40), null))
                .isFalse();
        assertThat(reopened.find("project-a")).get()
                .extracting(GitLabManagedProject::status)
                .isEqualTo(GitLabProjectStatus.DISABLED);
        assertThat(reopened.enableIfDisabled("project-a")).isTrue();
        assertThat(reopened.enableIfDisabled("project-a")).isFalse();
        assertThat(reopened.find("project-a")).get().satisfies(enabled -> {
            assertThat(enabled.status()).isEqualTo(GitLabProjectStatus.PENDING);
            assertThat(enabled.targetSha()).isNull();
            assertThat(enabled.lastError()).isNull();
        });
        assertThat(reopened.delete("project-a")).isTrue();
        assertThat(reopened.find("project-a")).isEmpty();
    }

    @Test
    void persistsJobTimelineWebhookStatusAndSecretUpdates() throws Exception {
        String database = Files.createTempDirectory("nexus-gitlab-job-store-")
                .resolve("projects.db").toString();
        GitLabIntegrationProperties properties = new GitLabIntegrationProperties(
                true, null, database, "", 10, 1);
        GitLabProjectStore store = new GitLabProjectStore(properties);
        store.save(project());

        String jobId = store.createJob("project-a", "MANUAL", "a".repeat(40), null);
        store.updateJob(jobId, "RUNNING", "FETCH", "b".repeat(40), null, null, false);
        store.updateJob(jobId, "SUCCEEDED", "PUBLISH", "b".repeat(40),
                null, "同步完成", true);
        store.recordWebhookStatus("project-a", "ACCEPTED", "event-2",
                "b".repeat(40), "Webhook 已接收");
        store.updateWebhookSecret("project-a", "rotated-ciphertext");

        GitLabProjectStore reopened = new GitLabProjectStore(properties);
        assertThat(reopened.findJob("project-a", jobId)).get().satisfies(job -> {
            assertThat(job.status()).isEqualTo("SUCCEEDED");
            assertThat(job.phase()).isEqualTo("PUBLISH");
            assertThat(job.targetSha()).isEqualTo("b".repeat(40));
            assertThat(job.finishedAt()).isNotBlank();
            assertThat(job.events()).extracting(GitLabSyncJob.Event::phase)
                    .containsExactly("QUEUED", "FETCH", "PUBLISH");
        });
        assertThat(reopened.webhookStatus("project-a")).get().satisfies(status -> {
            assertThat(status.status()).isEqualTo("ACCEPTED");
            assertThat(status.eventId()).isEqualTo("event-2");
            assertThat(status.targetSha()).isEqualTo("b".repeat(40));
        });
        assertThat(reopened.find("project-a")).get()
                .extracting(GitLabManagedProject::encryptedWebhookSecret)
                .isEqualTo("rotated-ciphertext");
    }

    @Test
    void marksQueuedJobsInterruptedWhenStoreReopens() throws Exception {
        String database = Files.createTempDirectory("nexus-gitlab-restart-job-")
                .resolve("projects.db").toString();
        GitLabIntegrationProperties properties = new GitLabIntegrationProperties(
                true, null, database, "", 10, 1);
        GitLabProjectStore store = new GitLabProjectStore(properties);
        store.save(project());
        String jobId = store.createJob("project-a", "INITIAL", null, null);

        GitLabProjectStore reopened = new GitLabProjectStore(properties);

        assertThat(reopened.findJob("project-a", jobId)).get().satisfies(job -> {
            assertThat(job.status()).isEqualTo("INTERRUPTED");
            assertThat(job.phase()).isEqualTo("INTERRUPTED");
            assertThat(job.errorCode()).isEqualTo("APPLICATION_RESTARTED");
            assertThat(job.finishedAt()).isNotBlank();
        });
    }

    @Test
    void persistsAndQueriesProjectsByConnectionWithoutBreakingLegacyRows() throws Exception {
        String database = Files.createTempDirectory("nexus-gitlab-project-connection-")
                .resolve("projects.db").toString();
        GitLabIntegrationProperties properties = new GitLabIntegrationProperties(
                true, null, database, "", 10, 1);
        GitLabProjectStore store = new GitLabProjectStore(properties);
        GitLabManagedProject legacy = project();
        GitLabManagedProject connected = new GitLabManagedProject(
                "project-b", "Project B", "group", "server",
                "https://gitlab.example.com/group/project-b.git", "main", "group/project-b",
                "project_b_requirements", "project_b_code", "/tmp/project-b",
                "connection-a", 22L, "", "encrypted-webhook", GitLabProjectStatus.PENDING,
                null, null, null, legacy.createdAt(), legacy.updatedAt());

        store.save(legacy);
        store.save(connected);

        GitLabProjectStore reopened = new GitLabProjectStore(properties);
        assertThat(reopened.find("project-a")).get()
                .extracting(GitLabManagedProject::connectionId)
                .isNull();
        assertThat(reopened.findByConnectionId("connection-a"))
                .extracting(GitLabManagedProject::projectId)
                .containsExactly("project-b");
        assertThat(reopened.findByRemoteProject("connection-a", 22)).get()
                .extracting(GitLabManagedProject::gitPath)
                .isEqualTo("group/project-b");
        assertThat(reopened.findByRemoteProject("connection-b", 22)).isEmpty();
    }

    @Test
    void atomicallyRejectsDuplicateProjectIdAndRemoteIdentityWithoutOverwritingWinner() throws Exception {
        String database = Files.createTempDirectory("nexus-gitlab-project-insert-")
                .resolve("projects.db").toString();
        GitLabIntegrationProperties properties = new GitLabIntegrationProperties(
                true, null, database, "", 10, 1);
        GitLabProjectStore store = new GitLabProjectStore(properties);
        String now = Instant.now().toString();
        GitLabManagedProject winner = new GitLabManagedProject(
                "orders", "Orders", "group", "server",
                "https://gitlab-a.example.com/group/orders.git", "main", "group/orders",
                "orders_requirements", "orders_code", "/tmp/orders",
                "connection-a", 11L, "", "winner-secret", GitLabProjectStatus.PENDING,
                null, null, null, now, now);
        GitLabManagedProject sameProjectId = new GitLabManagedProject(
                "orders", "Other", "other", "server",
                "https://gitlab-b.example.com/other/orders.git", "main", "other/orders",
                "other_requirements", "other_code", "/tmp/other",
                "connection-b", 22L, "", "loser-secret", GitLabProjectStatus.PENDING,
                null, null, null, now, now);
        GitLabManagedProject sameRemote = new GitLabManagedProject(
                "orders-copy", "Orders copy", "group", "server",
                "https://gitlab-a.example.com/group/renamed.git", "main", "group/renamed",
                "copy_requirements", "copy_code", "/tmp/copy",
                "connection-a", 11L, "", "loser-secret", GitLabProjectStatus.PENDING,
                null, null, null, now, now);

        assertThat(store.insert(winner)).isTrue();
        assertThat(store.insert(sameProjectId)).isFalse();
        assertThat(store.insert(sameRemote)).isFalse();

        assertThat(store.all()).singleElement().satisfies(saved -> {
            assertThat(saved.name()).isEqualTo("Orders");
            assertThat(saved.connectionId()).isEqualTo("connection-a");
            assertThat(saved.remoteProjectId()).isEqualTo(11L);
            assertThat(saved.encryptedWebhookSecret()).isEqualTo("winner-secret");
        });
    }

    @Test
    void deletingProjectCascadesJobsEventsAndWebhookStatus() throws Exception {
        String database = Files.createTempDirectory("nexus-gitlab-delete-")
                .resolve("projects.db").toString();
        GitLabIntegrationProperties properties = new GitLabIntegrationProperties(
                true, null, database, "", 10, 1);
        GitLabProjectStore store = new GitLabProjectStore(properties);
        store.save(project());
        String jobId = store.createJob("project-a", "MANUAL", null, null);
        store.updateJob(jobId, "RUNNING", "FETCH", null, null, null, false);
        store.recordWebhookEvent("project-a", "event-1");
        store.recordWebhookStatus("project-a", "ACCEPTED", "event-1", null, "已接收");

        assertThat(store.delete("project-a")).isTrue();

        assertThat(rowCount(database, "gitlab_managed_project")).isZero();
        assertThat(rowCount(database, "gitlab_webhook_event")).isZero();
        assertThat(rowCount(database, "gitlab_sync_job")).isZero();
        assertThat(rowCount(database, "gitlab_sync_event")).isZero();
        assertThat(rowCount(database, "gitlab_webhook_status")).isZero();
    }

    private int rowCount(String database, String table) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return results.getInt(1);
        }
    }

    private GitLabManagedProject project() {
        String now = Instant.now().toString();
        return new GitLabManagedProject("project-a", "Project A", "group", "server",
                "https://gitlab.example.com/group/project-a.git", "main", "group/project-a",
                "project_a_requirements", "project_a_code", "/tmp/project-a",
                "encrypted-pat", "encrypted-webhook", GitLabProjectStatus.PENDING,
                null, null, null, now, now);
    }
}
