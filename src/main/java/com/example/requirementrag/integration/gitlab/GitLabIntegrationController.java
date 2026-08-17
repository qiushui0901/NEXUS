package com.example.requirementrag.integration.gitlab;

import com.example.requirementrag.model.Permission;
import com.example.requirementrag.web.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 仅超级管理员可用的 GitLab 项目自动接入管理 API。 */
@RestController
@RequestMapping("/api/integrations/gitlab/projects")
@RequiresPermission(Permission.ADMIN)
@ConditionalOnProperty(name = "app.rag.gitlab.enabled", havingValue = "true")
public class GitLabIntegrationController {

    private final GitLabSyncService service;

    public GitLabIntegrationController(GitLabSyncService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GitLabManagedProject.View create(@RequestBody GitLabSyncService.CreateProject request) {
        return service.register(request);
    }

    @GetMapping
    public List<GitLabManagedProject.View> list() {
        return service.list();
    }

    @GetMapping("/{projectId}")
    public GitLabManagedProject.View get(@PathVariable String projectId) {
        return service.require(projectId);
    }

    @PostMapping("/{projectId}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GitLabManagedProject.View sync(@PathVariable String projectId) {
        return service.sync(projectId);
    }

    @PostMapping("/{projectId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GitLabManagedProject.View retry(@PathVariable String projectId) {
        return service.retry(projectId);
    }

    @DeleteMapping("/{projectId}")
    public GitLabManagedProject.View disable(@PathVariable String projectId) {
        return service.disable(projectId);
    }
}
