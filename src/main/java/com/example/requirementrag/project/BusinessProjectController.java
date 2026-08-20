package com.example.requirementrag.project;

import com.example.requirementrag.model.Permission;
import com.example.requirementrag.web.ProjectAccessGuard;
import com.example.requirementrag.web.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 业务项目、仓库归属、公共库引用与显式迁移 API。 */
@RestController
@RequestMapping("/api/business-projects")
public class BusinessProjectController {

    private final BusinessProjectCatalogService catalog;
    private final BusinessProjectSummaryService summaryService;
    private final BusinessProjectMigrationService migrationService;
    private final ProjectAccessGuard accessGuard;

    public BusinessProjectController(BusinessProjectCatalogService catalog,
                                     BusinessProjectSummaryService summaryService,
                                     BusinessProjectMigrationService migrationService,
                                     ProjectAccessGuard accessGuard) {
        this.catalog = catalog;
        this.summaryService = summaryService;
        this.migrationService = migrationService;
        this.accessGuard = accessGuard;
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping
    public List<BusinessProjectSummaryService.ProjectSummary> projects(HttpServletRequest request) {
        var user = accessGuard.currentUser(request);
        return catalog.projects().stream()
                .filter(project -> user.hasAccessTo(project.id())
                        || user.hasAccessTo(project.requirementSnapshotNamespace()))
                .map(summaryService::summary)
                .toList();
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/{projectId}")
    public ProjectDetail project(@PathVariable String projectId, HttpServletRequest request) {
        BusinessProject project = requireAccess(projectId, request);
        return detail(project);
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/{projectId}/repositories")
    public List<RepositoryView> repositories(@PathVariable String projectId, HttpServletRequest request) {
        BusinessProject project = requireAccess(projectId, request);
        return catalog.ownedRepositories(project.id()).stream().map(this::view).toList();
    }

    @RequiresPermission(Permission.WRITE)
    @PutMapping("/{projectId}/shared-repositories/{repositoryId}")
    public ProjectDetail addShared(@PathVariable String projectId, @PathVariable String repositoryId,
                                   HttpServletRequest request) {
        BusinessProject project = requireAccess(projectId, request);
        catalog.addSharedReference(project.id(), repositoryId);
        return detail(project);
    }

    @RequiresPermission(Permission.WRITE)
    @DeleteMapping("/{projectId}/shared-repositories/{repositoryId}")
    public ProjectDetail removeShared(@PathVariable String projectId, @PathVariable String repositoryId,
                                      HttpServletRequest request) {
        BusinessProject project = requireAccess(projectId, request);
        catalog.removeSharedReference(project.id(), repositoryId);
        return detail(project);
    }

    @RequiresPermission(Permission.ADMIN)
    @PostMapping("/migrations/preview")
    public BusinessProjectMigrationService.Preview preview(
            @RequestBody BusinessProjectMigrationService.MigrationRequest request) {
        return migrationService.preview(request);
    }

    @RequiresPermission(Permission.ADMIN)
    @PostMapping("/migrations/apply")
    public BusinessProjectMigrationService.Preview apply(
            @RequestBody BusinessProjectMigrationService.MigrationRequest request) {
        return migrationService.apply(request);
    }

    private BusinessProject requireAccess(String projectId, HttpServletRequest request) {
        BusinessProject project = catalog.requireProject(projectId);
        var user = accessGuard.currentUser(request);
        if (!user.hasAccessTo(project.id()) && !user.hasAccessTo(project.requirementSnapshotNamespace())) {
            throw new com.example.requirementrag.web.AccessDeniedException("Insufficient permissions");
        }
        return project;
    }

    private ProjectDetail detail(BusinessProject project) {
        return new ProjectDetail(summaryService.summary(project),
                catalog.ownedRepositories(project.id()).stream().map(this::view).toList(),
                catalog.sharedRepositories(project.id()).stream().map(this::view).toList());
    }

    private RepositoryView view(CodeRepository repository) {
        return new RepositoryView(repository.id(), repository.name(), repository.kind().name(),
                repository.side(), repository.gitPath(), repository.codeCollection(), repository.enabled());
    }

    public record ProjectDetail(BusinessProjectSummaryService.ProjectSummary summary,
                                List<RepositoryView> repositories,
                                List<RepositoryView> sharedRepositories) {}

    public record RepositoryView(String id, String name, String kind, String side,
                                 String gitPath, String codeCollection, boolean enabled) {}
}
