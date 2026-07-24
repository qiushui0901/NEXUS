package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.wiki.WikiGenerationService;
import com.example.requirementrag.wiki.WikiModels.GenerationResult;
import com.example.requirementrag.wiki.WikiModels.Page;
import com.example.requirementrag.wiki.WikiModels.ProjectSummary;
import com.example.requirementrag.wiki.WikiModels.VersionIndex;
import com.example.requirementrag.wiki.WikiRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Browse and publish versioned product, code and test knowledge. */
@RestController
@RequestMapping("/api/wiki")
public class WikiController {
    private final WikiRepository repository;
    private final WikiGenerationService generationService;
    private final ProjectRegistry projectRegistry;
    private final ProjectAccessGuard accessGuard;

    public WikiController(WikiRepository repository, WikiGenerationService generationService,
                          ProjectRegistry projectRegistry, ProjectAccessGuard accessGuard) {
        this.repository = repository;
        this.generationService = generationService;
        this.projectRegistry = projectRegistry;
        this.accessGuard = accessGuard;
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/projects")
    public List<ProjectSummary> projects(HttpServletRequest request) {
        UserContext user = accessGuard.currentUser(request);
        return repository.listProjects().stream()
                .filter(project -> user.hasAccessTo(project.projectId()))
                .toList();
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/versions")
    public List<VersionIndex> versions(@RequestParam String projectId, HttpServletRequest request) {
        requireAccess(projectId, request);
        return repository.listVersions(projectId);
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/index")
    public VersionIndex index(@RequestParam String projectId, @RequestParam String version,
                              HttpServletRequest request) {
        requireAccess(projectId, request);
        return repository.getIndex(projectId, version);
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/page")
    public Page page(@RequestParam String projectId, @RequestParam String version,
                     @RequestParam String featureId, HttpServletRequest request) {
        requireAccess(projectId, request);
        return repository.getPage(projectId, version, featureId);
    }

    @RequiresPermission(Permission.WRITE)
    @PostMapping("/generate")
    public GenerationResult generate(@RequestParam String projectId, @RequestParam String version,
                                     HttpServletRequest request) {
        requireAccess(projectId, request);
        return generationService.generate(projectId, version);
    }

    private void requireAccess(String projectId, HttpServletRequest request) {
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(request, projectId);
    }
}
