package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.requirement.semantic.RequirementSemanticBuildService;
import com.example.requirementrag.requirement.semantic.RequirementSemanticException;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildRecord;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildRequest;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildResult;
import com.example.requirementrag.requirement.semantic.SQLiteRequirementSemanticStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * 需求语义标注构建 HTTP API（`app.rag.requirement-semantic.enabled=true` 时装配）：
 * POST /api/requirement-semantic/builds 触发一次构建，GET /builds/latest 查询最近构建状态。
 * 构建是同步长任务（受 max-wall-clock-seconds 预算约束），失败以稳定错误码返回 400。
 */
@RestController
@RequestMapping("/api/requirement-semantic")
@ConditionalOnProperty(prefix = "app.rag.requirement-semantic", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class RequirementSemanticBuildController {

    private final RequirementSemanticBuildService buildService;
    private final SQLiteRequirementSemanticStore store;
    private final ProjectRegistry projectRegistry;
    private final ProjectAccessGuard accessGuard;

    public RequirementSemanticBuildController(RequirementSemanticBuildService buildService,
                                              SQLiteRequirementSemanticStore store,
                                              ProjectRegistry projectRegistry,
                                              ProjectAccessGuard accessGuard) {
        this.buildService = buildService;
        this.store = store;
        this.projectRegistry = projectRegistry;
        this.accessGuard = accessGuard;
    }

    /** 触发一次语义标注构建；retryFailedOnly=true 时只重跑上次失败项。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/builds")
    public SemanticBuildResult build(@RequestBody SemanticBuildRequest request,
                                     HttpServletRequest httpRequest) {
        if (request == null) {
            throw new RequirementSemanticException("SEMANTIC_REQUEST_INVALID", "语义构建请求不能为空");
        }
        projectRegistry.require(request.projectId());
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return buildService.build(request);
    }

    /** 查询项目/文档/版本最近一次构建记录（任意状态），供构建状态轮询。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/builds/latest")
    public Optional<SemanticBuildRecord> latestBuild(@RequestParam("projectId") String projectId,
                                                     @RequestParam("documentId") String documentId,
                                                     @RequestParam("requirementVersion") String requirementVersion,
                                                     HttpServletRequest httpRequest) {
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(httpRequest, projectId);
        return store.latestBuild(projectId, documentId, requirementVersion);
    }
}
