package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.requirement.graph.document.DocumentLevelBuildService;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.DocumentLevelBuildResult;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.DocumentStructureNode;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.EvidenceBundle;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.LogicalUnit;
import com.example.requirementrag.requirement.graph.document.RequirementDocumentStructureStore;
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
 * 文档级需求抽取 API（改进方案 Phase 0-4）：
 * 构建文档结构/逻辑单元/跨窗关系/证据包与构建指纹，并提供结构浏览查询。
 */
@RestController
@RequestMapping("/api/requirement-graphs/document-level")
public class RequirementDocumentLevelController {

    private final ProjectRegistry projectRegistry;
    private final ProjectAccessGuard accessGuard;
    private final DocumentLevelBuildService buildService;
    private final RequirementDocumentStructureStore store;

    public RequirementDocumentLevelController(ProjectRegistry projectRegistry,
                                              ProjectAccessGuard accessGuard,
                                              DocumentLevelBuildService buildService,
                                              RequirementDocumentStructureStore store) {
        this.projectRegistry = projectRegistry;
        this.accessGuard = accessGuard;
        this.buildService = buildService;
        this.store = store;
    }

    /** 构建文档级需求抽取（结构 + 逻辑单元 + 跨窗关系 + 证据包 + 指纹）。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/build")
    public DocumentLevelBuildResult build(@RequestBody BuildRequest request, HttpServletRequest httpRequest) {
        projectRegistry.require(request.projectId());
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return buildService.build(request.documentId(), request.requirementVersion(),
                request.documentRevision(), request.text());
    }

    /** 查询文档结构树。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/structure")
    public List<DocumentStructureNode> structure(@RequestParam String projectId,
                                                 @RequestParam String documentId,
                                                 @RequestParam String version,
                                                 HttpServletRequest httpRequest) {
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(httpRequest, projectId);
        return store.findStructureNodes(documentId, version);
    }

    /** 查询逻辑单元。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/units")
    public List<LogicalUnit> units(@RequestParam String projectId,
                                   @RequestParam String documentId,
                                   @RequestParam String revision,
                                   HttpServletRequest httpRequest) {
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(httpRequest, projectId);
        return store.findLogicalUnits(documentId, revision);
    }

    /** 查询证据包。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/bundles")
    public List<EvidenceBundle> bundles(@RequestParam String projectId,
                                        @RequestParam String documentId,
                                        @RequestParam String version,
                                        HttpServletRequest httpRequest) {
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(httpRequest, projectId);
        return store.findEvidenceBundles(documentId, version);
    }

    /** 构建请求。 */
    public record BuildRequest(String projectId, String documentId, String requirementVersion,
                               String documentRevision, String text) {
    }
}