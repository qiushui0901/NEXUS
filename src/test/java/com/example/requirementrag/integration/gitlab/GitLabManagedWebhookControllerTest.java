package com.example.requirementrag.integration.gitlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitLabManagedWebhookControllerTest {

    @Test
    void acceptsMatchingPushAndPassesEventToSyncService() throws Exception {
        GitLabSyncService service = mock(GitLabSyncService.class);
        GitLabManagedProject project = project();
        when(service.authenticateWebhook("project-a", "secret")).thenReturn(project);
        when(service.acceptPush("project-a", "event-1", "a".repeat(40), "b".repeat(40)))
                .thenReturn(true);
        GitLabManagedWebhookController controller =
                new GitLabManagedWebhookController(service, new ObjectMapper());
        byte[] body = body("main", "group/project-a");

        var response = controller.push(
                "project-a", "secret", "Push Hook", "event-1", body);

        assertThat(response.get("status")).isEqualTo("accepted");
        verify(service).acceptPush("project-a", "event-1", "a".repeat(40), "b".repeat(40));
    }

    @Test
    void ignoresOtherBranchesAndRejectsWrongProject() throws Exception {
        GitLabSyncService service = mock(GitLabSyncService.class);
        when(service.authenticateWebhook("project-a", "secret")).thenReturn(project());
        GitLabManagedWebhookController controller =
                new GitLabManagedWebhookController(service, new ObjectMapper());

        assertThat(controller.push("project-a", "secret", "Push Hook", "event-1",
                body("develop", "group/project-a")).get("status")).isEqualTo("ignored");
        assertThatThrownBy(() -> controller.push("project-a", "secret", "Push Hook", "event-2",
                body("main", "other/project")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void rejectsWrongTokenAndMalformedJsonWithStableStatuses() {
        GitLabSyncService service = mock(GitLabSyncService.class);
        when(service.authenticateWebhook("project-a", "wrong"))
                .thenThrow(new SecurityException("secret detail"));
        when(service.authenticateWebhook("project-a", "secret")).thenReturn(project());
        GitLabManagedWebhookController controller =
                new GitLabManagedWebhookController(service, new ObjectMapper());

        assertThatThrownBy(() -> controller.push(
                "project-a", "wrong", "Push Hook", "event-1", "{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> controller.push(
                "project-a", "secret", "Push Hook", "event-2", "{".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> controller.push(
                "project-a", "secret", null, "event-3", "{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void returnsDuplicateWithoutSubmittingAnotherSync() {
        GitLabSyncService service = mock(GitLabSyncService.class);
        when(service.authenticateWebhook("project-a", "secret")).thenReturn(project());
        when(service.acceptPush("project-a", "event-1", "a".repeat(40), "b".repeat(40)))
                .thenReturn(false);
        GitLabManagedWebhookController controller =
                new GitLabManagedWebhookController(service, new ObjectMapper());

        assertThat(controller.push(
                "project-a", "secret", "Push Hook", "event-1", body("main", "group/project-a")))
                .containsEntry("status", "duplicate");
    }

    private byte[] body(String branch, String gitPath) {
        return ("""
                {"ref":"refs/heads/%s","before":"%s","after":"%s",
                 "project":{"path_with_namespace":"%s"}}
                """.formatted(branch, "a".repeat(40), "b".repeat(40), gitPath))
                .getBytes(StandardCharsets.UTF_8);
    }

    private GitLabManagedProject project() {
        return new GitLabManagedProject(
                "project-a", "Project A", "group", "server",
                "https://gitlab.example.com/group/project-a.git", "main", "group/project-a",
                "project_a_requirements", "project_a_code", "/tmp/project-a",
                "encrypted-pat", "encrypted-webhook", GitLabProjectStatus.READY,
                "a".repeat(40), "a".repeat(40), null, "now", "now");
    }
}
