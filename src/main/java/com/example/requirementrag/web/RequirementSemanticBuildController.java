package com.example.requirementrag.web;

import com.example.requirementrag.model.Permission;
import com.example.requirementrag.project.BusinessProjectCatalogService;
import com.example.requirementrag.requirement.semantic.RequirementSemanticBuildService;
import com.example.requirementrag.requirement.semantic.RequirementSemanticException;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildAggregateView;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildRequest;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildResult;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildStatusView;
import com.example.requirementrag.requirement.semantic.RequirementSemanticProperties;
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
    private final ProjectAccessGuard accessGuard;
    private final BusinessProjectCatalogService businessProjects;
    private final RequirementSemanticProperties properties;

    public RequirementSemanticBuildController(RequirementSemanticBuildService buildService,
                                              SQLiteRequirementSemanticStore store,
                                              ProjectAccessGuard accessGuard,
                                              BusinessProjectCatalogService businessProjects,
                                              RequirementSemanticProperties properties) {
        this.buildService = buildService;
        this.store = store;
        this.accessGuard = accessGuard;
        this.businessProjects = businessProjects;
        this.properties = properties;
    }

    /**
     * 项目 ID 规范化：页面返回的是业务项目 ID（如 immortal），而 legacy 静态配置的 ID 是别名
     * （如 immortal-game-service）。必须统一经业务项目目录解析为规范 ID 再鉴权/落库——
     * 否则用旧 ID 构建会以规范 ID 落库、后续按旧 ID 查询却永远查不到状态；
     * 用业务 ID 查询又会被 legacy ProjectRegistry 误拒（两个 ID 指向同一项目但存储键不一致）。
     * requireProject 本身回退 legacy 注册表，因此别名与静态 ID 都能通过校验。
     */
    private String normalizeProjectId(String projectId) {
        String resolved = businessProjects.resolveProjectId(projectId);
        businessProjects.requireProject(resolved);
        return resolved;
    }

    /** 触发一次语义标注构建；retryFailedOnly=true 时只重跑上次失败项。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/builds")
    public SemanticBuildResult build(@RequestBody SemanticBuildRequest request,
                                     HttpServletRequest httpRequest) {
        if (request == null) {
            throw new RequirementSemanticException("SEMANTIC_REQUEST_INVALID", "语义构建请求不能为空");
        }
        // 入口统一规范化业务项目 ID：鉴权与落库使用同一规范 ID，避免别名/规范名混用产生孤儿数据。
        String projectId = normalizeProjectId(request.projectId());
        accessGuard.requireProjectAccess(httpRequest, projectId);
        return buildService.build(request.withProjectId(projectId));
    }

    /**
     * 查询项目/文档/版本最近一次构建执行的状态视图（任意状态）：
     * latestRunStatus 区分最新执行与生效代际（generationActive/activeGenerationStatus），供构建状态轮询。
     */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/builds/latest")
    public Optional<SemanticBuildStatusView> latestBuild(@RequestParam("projectId") String projectId,
                                                         @RequestParam("documentId") String documentId,
                                                         @RequestParam("requirementVersion") String requirementVersion,
                                                         HttpServletRequest httpRequest) {
        String normalized = normalizeProjectId(projectId);
        accessGuard.requireProjectAccess(httpRequest, normalized);
        return store.latestBuild(normalized, documentId, requirementVersion);
    }

    /**
     * 项目/版本级聚合构建状态：语义检索按 projectId+version 召回该版本全部 active 文档，
     * 前端状态条按同样范围聚合（覆盖文档数 + 最新执行状态），避免单文档状态误导检索范围。
     * 同时返回 candidate/normative 检索开关，前端可区分“配置关闭”与“召回质量差”。
     */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/builds/aggregate")
    public Optional<SemanticBuildAggregateView> aggregateBuild(@RequestParam("projectId") String projectId,
                                                               @RequestParam("requirementVersion") String requirementVersion,
                                                               HttpServletRequest httpRequest) {
        String normalized = normalizeProjectId(projectId);
        accessGuard.requireProjectAccess(httpRequest, normalized);
        return store.aggregateBuildStatus(normalized, requirementVersion,
                properties.candidateRetrievalEnabled(), properties.normativeRetrievalEnabled());
    }
}
