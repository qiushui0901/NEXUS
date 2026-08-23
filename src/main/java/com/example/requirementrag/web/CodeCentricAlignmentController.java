package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.knowledge.multisource.alignment.BusinessConceptService;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricAlignmentStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.AlignmentRelation;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BuildResult;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DoubtImpact;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DoubtImpactBuildResult;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DriftReport;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.VersionContext;
import com.example.requirementrag.knowledge.multisource.alignment.CodeParameterAlignmentService;
import com.example.requirementrag.knowledge.multisource.alignment.CodeTestAlignmentService;
import com.example.requirementrag.knowledge.multisource.alignment.DoubtImpactService;
import com.example.requirementrag.knowledge.multisource.alignment.RequirementCodeDriftService;
import com.example.requirementrag.knowledge.multisource.alignment.VersionContextService;
import com.example.requirementrag.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 代码事实基线驱动的跨源对齐 API（改进方案 Phase 1-5）：
 * 版本上下文、业务概念、代码—参数、代码—测试、需求—代码漂移报告与存疑影响分析。
 */
@RestController
@RequestMapping("/api/knowledge/alignment")
public class CodeCentricAlignmentController {

    private final ProjectRegistry projectRegistry;
    private final ProjectAccessGuard accessGuard;
    private final VersionContextService versionContextService;
    private final BusinessConceptService businessConceptService;
    private final CodeParameterAlignmentService codeParameterAlignmentService;
    private final CodeTestAlignmentService codeTestAlignmentService;
    private final RequirementCodeDriftService requirementCodeDriftService;
    private final DoubtImpactService doubtImpactService;
    private final CodeCentricAlignmentStore alignmentStore;

    public CodeCentricAlignmentController(ProjectRegistry projectRegistry,
                                          ProjectAccessGuard accessGuard,
                                          VersionContextService versionContextService,
                                          BusinessConceptService businessConceptService,
                                          CodeParameterAlignmentService codeParameterAlignmentService,
                                          CodeTestAlignmentService codeTestAlignmentService,
                                          RequirementCodeDriftService requirementCodeDriftService,
                                          DoubtImpactService doubtImpactService,
                                          CodeCentricAlignmentStore alignmentStore) {
        this.projectRegistry = projectRegistry;
        this.accessGuard = accessGuard;
        this.versionContextService = versionContextService;
        this.businessConceptService = businessConceptService;
        this.codeParameterAlignmentService = codeParameterAlignmentService;
        this.codeTestAlignmentService = codeTestAlignmentService;
        this.requirementCodeDriftService = requirementCodeDriftService;
        this.doubtImpactService = doubtImpactService;
        this.alignmentStore = alignmentStore;
    }

    /** 解析并保存当前版本上下文（repository + commit + 环境）。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/version-context")
    public VersionContext resolveContext(@RequestBody ScopeRequest request, HttpServletRequest httpRequest) {
        projectRegistry.require(request.projectId());
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return versionContextService.resolve(request.projectId(), request.version(), request.environment());
    }

    /** 查询已保存的版本上下文。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/version-context")
    public List<VersionContext> contexts(@RequestParam String projectId, @RequestParam String version,
                                         HttpServletRequest httpRequest) {
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(httpRequest, projectId);
        return versionContextService.list(projectId, version);
    }

    /** 构建业务概念层（Phase 1）。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/concepts/build")
    public BuildResult buildConcepts(@RequestBody ScopeRequest request, HttpServletRequest httpRequest) {
        projectRegistry.require(request.projectId());
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return businessConceptService.build(request.projectId(), request.version());
    }

    /** 查询项目业务概念。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/concepts")
    public List<com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BusinessConcept> concepts(
            @RequestParam String projectId, HttpServletRequest httpRequest) {
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(httpRequest, projectId);
        return businessConceptService.concepts(projectId);
    }

    /** 构建代码—参数对齐关系（Phase 2）。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/code-parameter/build")
    public BuildResult buildCodeParameter(@RequestBody ScopeRequest request, HttpServletRequest httpRequest) {
        projectRegistry.require(request.projectId());
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return codeParameterAlignmentService.build(request.projectId(), request.version(), request.environment());
    }

    /** 查询代码—参数对齐关系。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/code-parameter")
    public List<com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.AlignmentRelation> codeParameter(
            @RequestParam String projectId, @RequestParam String version,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String relationType, HttpServletRequest httpRequest) {
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(httpRequest, projectId);
        return codeParameterAlignmentService.relations(projectId, version, environment, relationType);
    }

    /** 构建代码—测试图谱（Phase 3）。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/code-test/build")
    public BuildResult buildCodeTest(@RequestBody ScopeRequest request, HttpServletRequest httpRequest) {
        projectRegistry.require(request.projectId());
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return codeTestAlignmentService.build(request.projectId(), request.version(), request.environment());
    }

    /** 查询代码—测试对齐关系。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/code-test")
    public List<com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.AlignmentRelation> codeTest(
            @RequestParam String projectId, @RequestParam String version,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String relationType, HttpServletRequest httpRequest) {
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(httpRequest, projectId);
        return codeTestAlignmentService.relations(projectId, version, environment, relationType);
    }

    /** 构建需求—代码漂移报告（Phase 4）。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/drift/build")
    public BuildResult buildDrift(@RequestBody ScopeRequest request, HttpServletRequest httpRequest) {
        projectRegistry.require(request.projectId());
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return requirementCodeDriftService.build(request.projectId(), request.version(), request.environment());
    }

    /** 查询需求—代码漂移报告。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/drift")
    public DriftReport drift(@RequestParam String projectId, @RequestParam String version,
                             @RequestParam(required = false) String environment, HttpServletRequest httpRequest) {
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(httpRequest, projectId);
        return requirementCodeDriftService.report(projectId, version, environment);
    }

    /** 构建 OPEN 存疑影响分析（Phase 5）。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/doubt-impact/build")
    public DoubtImpactBuildResult buildDoubtImpact(@RequestBody ScopeRequest request, HttpServletRequest httpRequest) {
        projectRegistry.require(request.projectId());
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return doubtImpactService.build(request.projectId(), request.version(), request.environment());
    }

    /** 查询指定环境下的存疑影响。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/doubt-impact")
    public List<DoubtImpact> doubtImpacts(@RequestParam String projectId, @RequestParam String version,
                                          @RequestParam(required = false) String environment,
                                          @RequestParam(required = false) String status,
                                          HttpServletRequest httpRequest) {
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(httpRequest, projectId);
        return doubtImpactService.impacts(projectId, version, environment, status);
    }

    /** 人工关闭存疑：绑定人工结论与 Resolution Evidence，并关闭对应影响项。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/doubt-impact/resolve")
    public List<DoubtImpact> resolveDoubtImpact(@RequestBody ResolveDoubtRequest request,
                                                HttpServletRequest httpRequest) {
        projectRegistry.require(request.projectId());
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return doubtImpactService.resolve(request.projectId(), request.version(), request.environment(),
                request.doubtId(), request.conclusion(), request.resolutionEvidenceId());
    }

    /** 人工审核对齐关系生命周期：HUMAN_CONFIRMED / REJECTED / STALE。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/alignment-relation/review")
    public AlignmentRelation reviewAlignmentRelation(@RequestBody ReviewAlignmentRelationRequest request,
                                                     HttpServletRequest httpRequest) {
        projectRegistry.require(request.projectId());
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        alignmentStore.reviewAlignmentRelation(request.relationId(), request.action());
        return alignmentStore.findAlignmentRelationById(request.relationId())
                .orElseThrow(() -> new IllegalArgumentException("未找到对齐关系: " + request.relationId()));
    }

    /** 对齐关系审核请求。 */
    public record ReviewAlignmentRelationRequest(String projectId, String relationId, String action) {
    }

    /** 存疑关闭请求。 */
    public record ResolveDoubtRequest(String projectId, String version, String environment,
                                      String doubtId, String conclusion, String resolutionEvidenceId) {
    }

    /** 对齐作用域请求。 */
    public record ScopeRequest(String projectId, String version, String environment) {
    }
}