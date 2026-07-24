package com.example.requirementrag.web;

import com.example.requirementrag.knowledge.build.KnowledgeBuildModels.BuildRequest;
import com.example.requirementrag.knowledge.build.KnowledgeBuildModels.BuildResult;
import com.example.requirementrag.knowledge.build.VersionKnowledgeBuildPipeline;
import com.example.requirementrag.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Creates reviewable version-knowledge drafts; it never publishes the formal Wiki. */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeBuildController {
    private final VersionKnowledgeBuildPipeline buildPipeline;
    private final ProjectAccessGuard accessGuard;

    public KnowledgeBuildController(VersionKnowledgeBuildPipeline buildPipeline, ProjectAccessGuard accessGuard) {
        this.buildPipeline = buildPipeline;
        this.accessGuard = accessGuard;
    }

    @RequiresPermission(Permission.WRITE)
    @PostMapping("/build")
    public BuildResult build(@Valid @RequestBody BuildRequest request, HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return buildPipeline.build(request);
    }
}
