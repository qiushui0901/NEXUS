package com.example.requirementrag.web;

import com.example.requirementrag.knowledge.build.KnowledgeBuildModels.BuildRequest;
import com.example.requirementrag.knowledge.build.KnowledgeBuildModels.BuildResult;
import com.example.requirementrag.knowledge.build.VersionKnowledgeBuildPipeline;
import com.example.requirementrag.knowledge.build.KnowledgeDraftLifecycleService;
import com.example.requirementrag.knowledge.build.KnowledgeDraftModels.DraftMetadata;
import com.example.requirementrag.knowledge.build.KnowledgeDraftModels.PublishResult;
import com.example.requirementrag.knowledge.build.KnowledgeDraftModels.RollbackResult;
import com.example.requirementrag.knowledge.build.KnowledgeDraftModels.TransitionRequest;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Creates reviewable version-knowledge drafts; it never publishes the formal Wiki. */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeBuildController {
    private final VersionKnowledgeBuildPipeline buildPipeline;
    private final ProjectAccessGuard accessGuard;
    private final KnowledgeDraftLifecycleService draftLifecycleService;
    private final ProjectRegistry projectRegistry;

    @Autowired
    public KnowledgeBuildController(VersionKnowledgeBuildPipeline buildPipeline, ProjectAccessGuard accessGuard,
                                    KnowledgeDraftLifecycleService draftLifecycleService,
                                    ProjectRegistry projectRegistry) {
        this.buildPipeline = buildPipeline;
        this.accessGuard = accessGuard;
        this.draftLifecycleService = draftLifecycleService;
        this.projectRegistry = projectRegistry;
    }

    /** Keeps standalone controller tests source-compatible. */
    public KnowledgeBuildController(VersionKnowledgeBuildPipeline buildPipeline, ProjectAccessGuard accessGuard) {
        this(buildPipeline, accessGuard, null, null);
    }

    @RequiresPermission(Permission.WRITE)
    @PostMapping("/build")
    public BuildResult build(@Valid @RequestBody BuildRequest request, HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return buildPipeline.build(request, accessGuard.currentUser(httpRequest).username());
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/drafts")
    public List<DraftMetadata> drafts(@RequestParam String projectId, @RequestParam String version,
                                      HttpServletRequest request) {
        requireDraftService();
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(request, projectId);
        return draftLifecycleService.list(projectId, version);
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/drafts/{buildId}")
    public DraftMetadata draft(@PathVariable String buildId, @RequestParam String projectId,
                               @RequestParam String version, HttpServletRequest request) {
        requireDraftService();
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(request, projectId);
        return draftLifecycleService.get(projectId, version, buildId);
    }

    @RequiresPermission(Permission.WRITE)
    @PostMapping("/drafts/{buildId}/transition")
    public DraftMetadata transition(@PathVariable String buildId, @RequestParam String projectId,
                                    @RequestParam String version, @Valid @RequestBody TransitionRequest transition,
                                    HttpServletRequest request) {
        requireDraftService();
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(request, projectId);
        return draftLifecycleService.transition(projectId, version, buildId, transition.targetStatus(),
                accessGuard.currentUser(request).username(), transition.comment());
    }

    @RequiresPermission(Permission.WRITE)
    @PostMapping("/drafts/{buildId}/publish")
    public PublishResult publish(@PathVariable String buildId, @RequestParam String projectId,
                                 @RequestParam String version, @RequestParam(required = false) String comment,
                                 HttpServletRequest request) {
        requireDraftService();
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(request, projectId);
        return draftLifecycleService.publish(projectId, version, buildId,
                accessGuard.currentUser(request).username(), comment);
    }

    @RequiresPermission(Permission.WRITE)
    @PostMapping("/drafts/{buildId}/rollback")
    public RollbackResult rollback(@PathVariable String buildId, @RequestParam String projectId,
                                   @RequestParam String version, @RequestParam(required = false) String comment,
                                   HttpServletRequest request) {
        requireDraftService();
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(request, projectId);
        return draftLifecycleService.rollback(projectId, version, buildId,
                accessGuard.currentUser(request).username(), comment);
    }

    private void requireDraftService() {
        if (draftLifecycleService == null || projectRegistry == null) {
            throw new IllegalStateException("知识草稿生命周期服务未配置");
        }
    }
}
