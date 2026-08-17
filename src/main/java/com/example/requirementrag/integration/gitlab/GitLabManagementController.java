package com.example.requirementrag.integration.gitlab;

import com.example.requirementrag.model.Permission;
import com.example.requirementrag.web.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** GitLab 管理工作台使用的预检、任务历史和 Webhook 管理 API。 */
@RestController
@RequestMapping("/api/integrations/gitlab")
@RequiresPermission(Permission.ADMIN)
@ConditionalOnProperty(name = "app.rag.gitlab.enabled", havingValue = "true")
public class GitLabManagementController {

    private final GitLabSyncService service;

    public GitLabManagementController(GitLabSyncService service) {
        this.service = service;
    }

    @PostMapping("/validate-connection")
    public GitLabGitClient.ValidationResult validateConnection(
            @RequestBody GitLabSyncService.ValidateConnection request) {
        return service.validateConnection(request);
    }

    @PostMapping("/validate-project")
    public GitLabSyncService.ValidationResponse validateProject(
            @RequestBody GitLabSyncService.ValidateProject request) {
        return service.validateProject(request);
    }

    @PostMapping("/projects/validate-config")
    public GitLabSyncService.ValidationResponse validateConfig(
            @RequestBody GitLabSyncService.ValidateConfig request) {
        return service.validateConfig(request);
    }

    @GetMapping("/projects/{projectId}/jobs")
    public List<GitLabSyncJob> jobs(@PathVariable String projectId) {
        return service.jobs(projectId);
    }

    @GetMapping("/projects/{projectId}/jobs/{jobId}")
    public GitLabSyncJob job(@PathVariable String projectId, @PathVariable String jobId) {
        return service.job(projectId, jobId);
    }

    @GetMapping("/projects/{projectId}/webhook-status")
    public GitLabWebhookStatus webhookStatus(@PathVariable String projectId) {
        return service.webhookStatus(projectId);
    }

    @PostMapping("/projects/{projectId}/webhook-secret/rotate")
    public GitLabSyncService.RotatedSecret rotateSecret(@PathVariable String projectId) {
        return service.rotateWebhookSecret(projectId);
    }
}
