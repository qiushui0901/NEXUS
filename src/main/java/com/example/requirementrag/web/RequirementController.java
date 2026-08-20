package com.example.requirementrag.web;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.knowledge.management.KnowledgeIngestionTracker;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.SourceType;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.TriggerType;
import com.example.requirementrag.model.DoubtBatch;
import com.example.requirementrag.model.IngestResponse;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.ReviewRequest;
import com.example.requirementrag.project.BusinessProjectCatalogService;
import com.example.requirementrag.service.DoubtExportService;
import com.example.requirementrag.service.ReviewFacadeService;
import com.example.requirementrag.service.RequirementIngestionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 需求文档导入、评审与导出 REST 接口。
 */
@RestController
@RequestMapping("/api/requirements")
public class RequirementController {

    private final RequirementIngestionService ingestionService;
    private final ReviewFacadeService reviewFacade;
    private final DoubtExportService exportService;
    private final ProjectRegistry projectRegistry;
    private final ProjectAccessGuard accessGuard;
    private final KnowledgeIngestionTracker ingestionTracker;
    private final BusinessProjectCatalogService businessProjects;

    public RequirementController(RequirementIngestionService ingestionService, ReviewFacadeService reviewFacade,
                                 DoubtExportService exportService, ProjectRegistry projectRegistry,
                                 ProjectAccessGuard accessGuard) {
        this(ingestionService, reviewFacade, exportService, projectRegistry, accessGuard,
                (KnowledgeIngestionTracker) null, (BusinessProjectCatalogService) null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RequirementController(RequirementIngestionService ingestionService, ReviewFacadeService reviewFacade,
                                 DoubtExportService exportService, ProjectRegistry projectRegistry,
                                 ProjectAccessGuard accessGuard,
                                 org.springframework.beans.factory.ObjectProvider<KnowledgeIngestionTracker> tracker,
                                 org.springframework.beans.factory.ObjectProvider<BusinessProjectCatalogService> businessProjects) {
        this(ingestionService, reviewFacade, exportService, projectRegistry, accessGuard,
                tracker.getIfAvailable(), businessProjects.getIfAvailable());
    }

    private RequirementController(RequirementIngestionService ingestionService, ReviewFacadeService reviewFacade,
                                  DoubtExportService exportService, ProjectRegistry projectRegistry,
                                  ProjectAccessGuard accessGuard, KnowledgeIngestionTracker ingestionTracker,
                                  BusinessProjectCatalogService businessProjects) {
        this.ingestionService = ingestionService;
        this.reviewFacade = reviewFacade;
        this.exportService = exportService;
        this.projectRegistry = projectRegistry;
        this.accessGuard = accessGuard;
        this.ingestionTracker = ingestionTracker;
        this.businessProjects = businessProjects;
    }

    /** 上传文档并导入向量库，同时记录知识管理运行状态。projectId 可选。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestResponse ingest(
            @RequestPart("file") MultipartFile file,
            @RequestParam @NotBlank String version,
            @RequestParam(required = false) String documentId,
            @RequestParam(required = false) String projectId,
            HttpServletRequest httpRequest) throws IOException {
        accessGuard.requireProjectAccess(httpRequest, projectId);
        String collection = resolveRequirementCollection(projectId);
        String knowledgeProjectId = resolveKnowledgeProjectId(projectId);
        KnowledgeIngestionTracker.Context context = startUploadTracking(knowledgeProjectId, collection, version);
        try {
            IngestResponse response = ingestionService.ingest(collection, file, version, documentId, context);
            if (ingestionTracker != null) ingestionTracker.complete(context, response.chunks());
            return response;
        } catch (IOException | RuntimeException exception) {
            if (ingestionTracker != null) ingestionTracker.fail(context, exception);
            throw exception;
        }
    }

    private KnowledgeIngestionTracker.Context startUploadTracking(String projectId, String collection,
                                                                    String version) {
        if (ingestionTracker == null) return KnowledgeIngestionTracker.Context.disabled();
        return ingestionTracker.start(projectId, projectId, collection, version,
                TriggerType.MANUAL, SourceType.UPLOAD);
    }

    private String resolveKnowledgeProjectId(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return projectRegistry.find(null).map(RagProperties.ProjectConfig::id).orElse("default");
        }
        return businessProjects == null ? projectId : businessProjects.resolveProjectId(projectId);
    }

    /** 执行需求存疑评审并返回 JSON 结果。 */
    @RequiresPermission(Permission.OPERATE)
    @PostMapping("/reviews")
    public DoubtBatch review(@Valid @RequestBody ReviewRequest request, HttpServletRequest httpRequest)
            throws IOException {
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return reviewFacade.review(request);
    }

    /** 执行评审并将结果导出为 XLSX 附件下载。 */
    @RequiresPermission(Permission.OPERATE)
    @PostMapping("/reviews/export")
    public ResponseEntity<byte[]> exportReview(@Valid @RequestBody ReviewRequest request,
                                               HttpServletRequest httpRequest) throws IOException {
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        DoubtBatch batch = reviewFacade.review(request);
        byte[] xlsx = exportService.toXlsx(batch, request.version());
        String filename = resolveExportFilename(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(xlsx);
    }

    /** 按业务项目解析共享需求 collection；未指定项目时返回 null 走全局默认。 */
    private String resolveRequirementCollection(String projectId) {
        if (projectId == null || projectId.isBlank()) return null;
        if (businessProjects != null) {
            return businessProjects.requireProject(projectId).requirementCollection();
        }
        return projectRegistry.resolveRequirementCollection(projectId);
    }

    /** 生成导出文件名：文档ID（缺省为「需求」）+ 版本 + 存疑.xlsx。 */
    private String resolveExportFilename(ReviewRequest request) {
        String prefix = request.documentId() != null ? request.documentId() : "需求";
        return prefix + request.version() + "存疑.xlsx";
    }
}
