package com.example.requirementrag.integration.gitlab;

import com.example.requirementrag.model.Permission;
import com.example.requirementrag.web.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** GitLab 账号连接和项目发现管理 API。 */
@RestController
@RequestMapping("/api/integrations/gitlab/connections")
@RequiresPermission(Permission.ADMIN)
@ConditionalOnProperty(name = "app.rag.gitlab.enabled", havingValue = "true")
public class GitLabConnectionController {
    private final GitLabAccountService service;
    private final GitLabProjectImportService importService;

    public GitLabConnectionController(GitLabAccountService service,
                                      GitLabProjectImportService importService) {
        this.service = service;
        this.importService = importService;
    }

    @PostMapping
    public GitLabConnection.View create(@RequestBody GitLabAccountService.CreateConnection request) {
        return service.create(request);
    }

    @GetMapping
    public List<GitLabConnection.View> list() {
        return service.list();
    }

    @GetMapping("/{connectionId}")
    public GitLabConnection.View get(@PathVariable String connectionId) {
        return service.require(connectionId);
    }

    @PostMapping("/{connectionId}/verify")
    public GitLabConnection.View verify(@PathVariable String connectionId) {
        return service.verify(connectionId);
    }

    @PostMapping("/{connectionId}/reauthorize")
    public GitLabConnection.View reauthorize(@PathVariable String connectionId,
                                             @RequestBody GitLabAccountService.Reauthorize request) {
        return service.reauthorize(connectionId, request);
    }

    @DeleteMapping("/{connectionId}")
    public GitLabConnection.View disable(@PathVariable String connectionId) {
        return service.disable(connectionId);
    }

    @GetMapping("/{connectionId}/projects")
    public GitLabAccountService.ProjectPage projects(
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String query) {
        return service.projects(connectionId, page, size, query);
    }

    @PostMapping("/{connectionId}/imports")
    public GitLabProjectImportService.BatchImportResponse imports(
            @PathVariable String connectionId,
            @RequestBody GitLabProjectImportService.BatchImportRequest request) {
        return importService.importProjects(connectionId, request);
    }
}
