package com.example.requirementrag.integration.gitlab;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
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
        reopened.updateState("project-a", GitLabProjectStatus.DISABLED, null, null, null);
        assertThat(reopened.updateStateIfEnabled(
                "project-a", GitLabProjectStatus.READY, "b".repeat(40), "b".repeat(40), null))
                .isFalse();
        assertThat(reopened.find("project-a")).get()
                .extracting(GitLabManagedProject::status)
                .isEqualTo(GitLabProjectStatus.DISABLED);
        assertThat(reopened.delete("project-a")).isTrue();
        assertThat(reopened.find("project-a")).isEmpty();
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
