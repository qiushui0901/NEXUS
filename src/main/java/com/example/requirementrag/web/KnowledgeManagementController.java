package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.knowledge.KnowledgeBootstrapService;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.ActionAccepted;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.BaseType;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.ChunkView;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.DocumentView;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.EntityStatus;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.KnowledgeBaseView;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.Page;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.RetrievalHit;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.RetrievalTestRequest;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.RetrievalTestResponse;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.RunView;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.Stage;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.StageEventView;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.SummaryStatus;
import com.example.requirementrag.knowledge.management.SQLiteKnowledgeManagementStore;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalPipeline;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** RAGFlow 风格知识管理接口：查询导入状态、触发重建并复用正式检索链路测试召回。 */
@RestController
@RequestMapping("/api/knowledge-bases")
@ConditionalOnProperty(prefix = "app.knowledge-management", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class KnowledgeManagementController {
    private static final int CHILD_TEXT_LIMIT = 600;
    private static final int PARENT_TEXT_LIMIT = 1200;

    private final SQLiteKnowledgeManagementStore store;
    private final ProjectRegistry projectRegistry;
    private final ProjectAccessGuard accessGuard;
    private final KnowledgeBootstrapService bootstrapService;
    private final RetrievalPipeline retrievalPipeline;

    public KnowledgeManagementController(SQLiteKnowledgeManagementStore store,
                                         ProjectRegistry projectRegistry,
                                         ProjectAccessGuard accessGuard,
                                         KnowledgeBootstrapService bootstrapService,
                                         RetrievalPipeline retrievalPipeline) {
        this.store = store;
        this.projectRegistry = projectRegistry;
        this.accessGuard = accessGuard;
        this.bootstrapService = bootstrapService;
        this.retrievalPipeline = retrievalPipeline;
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping
    public Page<KnowledgeBaseView> list(@RequestParam(required = false) String projectId,
                                        @RequestParam(required = false) SummaryStatus status,
                                        @RequestParam(required = false) BaseType type,
                                        @RequestParam(required = false) String query,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size,
                                        HttpServletRequest request) {
        if (projectId != null && !projectId.isBlank()) {
            projectRegistry.require(projectId);
            accessGuard.requireProjectAccess(request, projectId);
            return store.listBases(projectId, status, type, query, page, size);
        }
        UserContext user = accessGuard.currentUser(request);
        List<String> accessibleProjects = projectRegistry.all().stream()
                .map(project -> project.id())
                .filter(id -> id != null && !id.isBlank())
                .filter(user::hasAccessTo)
                .toList();
        return store.listBasesForProjects(accessibleProjects, status, type, query, page, size);
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/{id}")
    public KnowledgeBaseView get(@PathVariable String id, HttpServletRequest request) {
        return requireBase(id, request);
    }

    @RequiresPermission(Permission.WRITE)
    @PostMapping("/{id}/rebuild")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ActionAccepted rebuild(@PathVariable String id, HttpServletRequest request) {
        KnowledgeBaseView base = requireBase(id, request);
        bootstrapService.bootstrapAsync(base.projectId());
        return accepted("PROJECT_REBUILD", base.projectId());
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/{id}/runs")
    public Page<RunView> runs(@PathVariable String id,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "50") int size,
                              HttpServletRequest request) {
        requireBase(id, request);
        return store.listRuns(id, page, size);
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/{id}/runs/{runId}")
    public RunView run(@PathVariable String id, @PathVariable String runId, HttpServletRequest request) {
        requireBase(id, request);
        return notFound(() -> store.requireRun(id, runId));
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/{id}/runs/{runId}/events")
    public List<StageEventView> events(@PathVariable String id, @PathVariable String runId,
                                       HttpServletRequest request) {
        requireBase(id, request);
        notFound(() -> store.requireRun(id, runId));
        return store.events(runId);
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/{id}/documents")
    public Page<DocumentView> documents(@PathVariable String id,
                                        @RequestParam(required = false) EntityStatus status,
                                        @RequestParam(required = false) Stage phase,
                                        @RequestParam(required = false) String query,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size,
                                        HttpServletRequest request) {
        requireBase(id, request);
        return store.listDocuments(id, status, phase, query, page, size);
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/{id}/documents/{documentId}")
    public DocumentView document(@PathVariable String id, @PathVariable String documentId,
                                 HttpServletRequest request) {
        requireBase(id, request);
        return notFound(() -> store.requireDocument(id, documentId));
    }

    @RequiresPermission(Permission.WRITE)
    @PostMapping("/{id}/documents/{documentId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ActionAccepted retryDocument(@PathVariable String id, @PathVariable String documentId,
                                        HttpServletRequest request) {
        KnowledgeBaseView base = requireBase(id, request);
        notFound(() -> store.requireDocument(id, documentId));
        bootstrapService.bootstrapAsync(base.projectId());
        return accepted("DOCUMENT_REBUILD", base.projectId());
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/{id}/documents/{documentId}/chunks")
    public Page<ChunkView> chunks(@PathVariable String id, @PathVariable String documentId,
                                  @RequestParam(required = false) EntityStatus status,
                                  @RequestParam(required = false) String query,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size,
                                  HttpServletRequest request) {
        requireBase(id, request);
        notFound(() -> store.requireDocument(id, documentId));
        return store.listChunks(documentId, status, query, page, size);
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/{id}/chunks/{chunkId}")
    public ChunkView chunk(@PathVariable String id, @PathVariable String chunkId,
                           HttpServletRequest request) {
        requireBase(id, request);
        return notFound(() -> store.requireChunkInBase(id, chunkId));
    }

    @RequiresPermission(Permission.WRITE)
    @PostMapping("/{id}/chunks/{chunkId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ActionAccepted retryChunk(@PathVariable String id, @PathVariable String chunkId,
                                     HttpServletRequest request) {
        KnowledgeBaseView base = requireBase(id, request);
        notFound(() -> store.requireChunkInBase(id, chunkId));
        bootstrapService.bootstrapAsync(base.projectId());
        return accepted("DOCUMENT_REBUILD", base.projectId());
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @PostMapping("/{id}/retrieval-tests")
    public RetrievalTestResponse testRetrieval(@PathVariable String id,
                                               @Valid @RequestBody RetrievalTestRequest body,
                                               HttpServletRequest request) {
        KnowledgeBaseView base = requireBase(id, request);
        RagOutcome<RetrievalBundle> outcome = retrievalPipeline.execute(new RetrievalRequest(
                body.query(), RetrievalProfile.REQUIREMENT_REVIEW, base.projectId(),
                body.documentId(), body.version(), body.limit()));
        RetrievalBundle data = outcome.data();
        List<ChunkRecord> evidence = data == null ? List.of() : data.requirementEvidence();
        AtomicInteger rank = new AtomicInteger(1);
        List<RetrievalHit> hits = evidence.stream()
                .map(chunk -> hit(rank.getAndIncrement(), chunk))
                .toList();
        return new RetrievalTestResponse(
                outcome.status(),
                data == null ? base.projectId() : data.resolvedProjectId(),
                data == null ? body.documentId() : data.documentId(),
                data == null ? body.version() : data.version(),
                hits,
                outcome.warnings(),
                outcome.stageDiagnostics());
    }

    private KnowledgeBaseView requireBase(String id, HttpServletRequest request) {
        KnowledgeBaseView base = notFound(() -> store.requireBase(id));
        projectRegistry.require(base.projectId());
        accessGuard.requireProjectAccess(request, base.projectId());
        return base;
    }

    private RetrievalHit hit(int rank, ChunkRecord chunk) {
        return new RetrievalHit(
                rank,
                chunk.id(),
                chunk.documentId(),
                chunk.version(),
                safeSourcePath(chunk.filename()),
                chunk.parentId(),
                chunk.parentOrder(),
                chunk.childOrder(),
                chunk.contentHash(),
                truncate(chunk.childText(), CHILD_TEXT_LIMIT),
                truncate(chunk.parentText(), PARENT_TEXT_LIMIT));
    }

    private String safeSourcePath(String value) {
        if (value == null || value.isBlank()) return value;
        String normalized = value.replace('\\', '/');
        boolean absolute = normalized.startsWith("/")
                || normalized.matches("^[A-Za-z]:/.*")
                || normalized.startsWith("file:");
        if (!absolute) return normalized;
        int separator = normalized.lastIndexOf('/');
        return separator >= 0 ? normalized.substring(separator + 1) : "source";
    }

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, limit);
    }

    private ActionAccepted accepted(String mode, String projectId) {
        return new ActionAccepted("accepted", mode, projectId);
    }

    private <T> T notFound(ResourceSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识管理资源不存在");
        }
    }

    @FunctionalInterface
    private interface ResourceSupplier<T> {
        T get();
    }
}
