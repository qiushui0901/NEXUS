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

/** 版本化产品、代码与测试知识库的浏览与发布接口。 */
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

    /** 列出当前用户有权访问的 Wiki 项目。对应 GET /api/wiki/projects。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/projects")
    public List<ProjectSummary> projects(HttpServletRequest request) {
        UserContext user = accessGuard.currentUser(request);
        return repository.listProjects().stream()
                .filter(project -> user.hasAccessTo(project.projectId()))
                .toList();
    }

    /** 列出指定项目的全部 Wiki 版本。对应 GET /api/wiki/versions。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/versions")
    public List<VersionIndex> versions(@RequestParam String projectId, HttpServletRequest request) {
        requireAccess(projectId, request);
        return repository.listVersions(projectId);
    }

    /** 获取指定项目版本的知识索引。对应 GET /api/wiki/index。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/index")
    public VersionIndex index(@RequestParam String projectId, @RequestParam String version,
                              HttpServletRequest request) {
        requireAccess(projectId, request);
        return repository.getIndex(projectId, version);
    }

    /** 获取指定版本下功能特性的页面内容。对应 GET /api/wiki/page。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/page")
    public Page page(@RequestParam String projectId, @RequestParam String version,
                     @RequestParam String featureId, HttpServletRequest request) {
        requireAccess(projectId, request);
        return repository.getPage(projectId, version, featureId);
    }

    /** 生成指定项目版本的 Wiki 知识。对应 POST /api/wiki/generate。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/generate")
    public GenerationResult generate(@RequestParam String projectId, @RequestParam String version,
                                     HttpServletRequest request) {
        requireAccess(projectId, request);
        return generationService.generate(projectId, version);
    }

    /** 校验项目存在且当前用户有访问权。 */
    private void requireAccess(String projectId, HttpServletRequest request) {
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(request, projectId);
    }
}
