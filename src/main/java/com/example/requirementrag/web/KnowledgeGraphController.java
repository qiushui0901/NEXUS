package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.knowledge.multisource.KnowledgeGraphBuildService;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 跨源总实体关系图 API：构建与查询。 */
@RestController
@RequestMapping("/api/knowledge/graph")
public class KnowledgeGraphController {

    private final MultiSourceKnowledgeStore store;
    private final KnowledgeGraphBuildService buildService;
    private final ProjectRegistry projectRegistry;
    private final ProjectAccessGuard accessGuard;

    public KnowledgeGraphController(MultiSourceKnowledgeStore store,
                                    KnowledgeGraphBuildService buildService,
                                    ProjectRegistry projectRegistry,
                                    ProjectAccessGuard accessGuard) {
        this.store = store;
        this.buildService = buildService;
        this.projectRegistry = projectRegistry;
        this.accessGuard = accessGuard;
    }

    /** 查询项目/版本的总实体关系图。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping
    public GraphResponse graph(@RequestParam String projectId, @RequestParam String version,
                               HttpServletRequest httpRequest) {
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(httpRequest, projectId);
        return new GraphResponse(store.findEntities(projectId, version),
                store.findEntityRelations(projectId, version));
    }

    /** 构建并持久化总实体关系图。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/build")
    public KnowledgeGraphBuildService.GraphBuildResult build(@RequestBody BuildRequest request,
                                                             HttpServletRequest httpRequest) {
        projectRegistry.require(request.projectId());
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return buildService.build(request.projectId(), request.version());
    }

    /** 构建请求。 */
    public record BuildRequest(String projectId, String version) {
    }

    /** 图查询响应。 */
    public record GraphResponse(
            List<com.example.requirementrag.knowledge.multisource.KnowledgeGraphModels.KnowledgeEntity> entities,
            List<com.example.requirementrag.knowledge.multisource.KnowledgeGraphModels.KnowledgeEntityRelation> relations) {
    }
}