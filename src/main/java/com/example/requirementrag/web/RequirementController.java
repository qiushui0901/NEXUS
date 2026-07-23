package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.DoubtBatch;
import com.example.requirementrag.model.IngestResponse;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.ReviewRequest;
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

    public RequirementController(RequirementIngestionService ingestionService, ReviewFacadeService reviewFacade,
                                 DoubtExportService exportService, ProjectRegistry projectRegistry,
                                 ProjectAccessGuard accessGuard) {
        this.ingestionService = ingestionService;
        this.reviewFacade = reviewFacade;
        this.exportService = exportService;
        this.projectRegistry = projectRegistry;
        this.accessGuard = accessGuard;
    }

    /** 上传文档并导入向量库。projectId 可选，指定后写入该项目的需求 collection。 */
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
        return ingestionService.ingest(collection, file, version, documentId);
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

    private String resolveRequirementCollection(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return null;
        }
        return projectRegistry.resolveRequirementCollection(projectId);
    }

    private String resolveExportFilename(ReviewRequest request) {
        String prefix = request.documentId() != null ? request.documentId() : "需求";
        return prefix + request.version() + "存疑.xlsx";
    }
}
