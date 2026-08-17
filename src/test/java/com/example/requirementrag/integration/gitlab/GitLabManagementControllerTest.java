package com.example.requirementrag.integration.gitlab;

import com.example.requirementrag.model.Permission;
import com.example.requirementrag.web.RequiresPermission;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitLabManagementControllerTest {

    @Test
    void requiresAdminPermissionAndDelegatesWorkbenchOperations() {
        RequiresPermission permission =
                GitLabManagementController.class.getAnnotation(RequiresPermission.class);
        assertThat(permission).isNotNull();
        assertThat(permission.value()).isEqualTo(Permission.ADMIN);

        GitLabSyncService service = mock(GitLabSyncService.class);
        GitLabManagementController controller = new GitLabManagementController(service);
        GitLabSyncService.ValidateConnection connection =
                new GitLabSyncService.ValidateConnection("https://gitlab.example.com/a/b.git",
                        "main", "token");
        GitLabSyncService.ValidateProject project =
                new GitLabSyncService.ValidateProject("project-a", "group/project-a");
        GitLabSyncService.ValidateConfig config =
                new GitLabSyncService.ValidateConfig("project_a_requirements", "project_a_code", null);
        GitLabGitClient.ValidationResult connectionResult =
                new GitLabGitClient.ValidationResult("gitlab.example.com", "group/project-a",
                        "main", "a".repeat(40), true);
        GitLabSyncService.ValidationResponse valid =
                new GitLabSyncService.ValidationResponse(true, "ok");
        GitLabSyncJob job = new GitLabSyncJob(
                "job-1", "project-a", "MANUAL", "SUCCEEDED", "PUBLISH",
                null, "a".repeat(40), null, null, null, "correlation",
                "started", "finished", List.of());
        GitLabWebhookStatus webhook =
                new GitLabWebhookStatus("project-a", "ACCEPTED", "event-1",
                        "a".repeat(40), "ok", "now");
        GitLabSyncService.RotatedSecret rotated =
                new GitLabSyncService.RotatedSecret("one-time-secret-value", "now");
        when(service.validateConnection(connection)).thenReturn(connectionResult);
        when(service.validateProject(project)).thenReturn(valid);
        when(service.validateConfig(config)).thenReturn(valid);
        when(service.jobs("project-a")).thenReturn(List.of(job));
        when(service.job("project-a", "job-1")).thenReturn(job);
        when(service.webhookStatus("project-a")).thenReturn(webhook);
        when(service.rotateWebhookSecret("project-a")).thenReturn(rotated);

        assertThat(controller.validateConnection(connection)).isSameAs(connectionResult);
        assertThat(controller.validateProject(project)).isSameAs(valid);
        assertThat(controller.validateConfig(config)).isSameAs(valid);
        assertThat(controller.jobs("project-a")).containsExactly(job);
        assertThat(controller.job("project-a", "job-1")).isSameAs(job);
        assertThat(controller.webhookStatus("project-a")).isSameAs(webhook);
        assertThat(controller.rotateSecret("project-a")).isSameAs(rotated);

        verify(service).validateConnection(connection);
        verify(service).validateProject(project);
        verify(service).validateConfig(config);
        verify(service).jobs("project-a");
        verify(service).job("project-a", "job-1");
        verify(service).webhookStatus("project-a");
        verify(service).rotateWebhookSecret("project-a");
    }
}
