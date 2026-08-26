package com.example.requirementrag.web;

import com.example.requirementrag.code.CodeIndexJobService;
import com.example.requirementrag.code.CodeQdrantStore;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.knowledge.KnowledgeBootstrapService;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.ActionAccepted;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.BaseType;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.ChunkView;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.CodeHit;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.DocumentView;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.EntityStatus;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.KnowledgeBaseView;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.Page;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.RetrievalHit;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.RetrievalTestRequest;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.RetrievalTestResponse;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.RunView;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.SourceType;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.Stage;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.StageEventView;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.SummaryStatus;
import com.example.requirementrag.knowledge.management.SQLiteKnowledgeManagementStore;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalPipeline;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.project.BusinessProject;
import com.example.requirementrag.project.BusinessProjectCatalogService;
import com.example.requirementrag.project.CodeRepository;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** RAGFlow 风格知识管理接口：查询导入状态、触发重建并复用正式检索链路测试召回。 */
@RestController
@RequestMapping("/api/knowledge-bases")
@ConditionalOnProperty(prefix = "app.knowledge-management", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class KnowledgeManagementController {
    private static final int CHILD_TEXT_LIMIT = 600;
    private static final int PARENT_TEXT_LIMIT = 1200;
    private static final int CODE_TEXT_LIMIT = 1200;

    private final SQLiteKnowledgeManagementStore store;
    private final ProjectRegistry projectRegistry;
    private final ProjectAccessGuard accessGuard;
    private final KnowledgeBootstrapService bootstrapService;
    private final RetrievalPipeline retrievalPipeline;
    private final QdrantHybridStore qdrantStore;
    private final CodeQdrantStore codeStore;
    private final CodeIndexJobService codeIndexJobService;
    private final BusinessProjectCatalogService businessProjects;

    @Autowired
    public KnowledgeManagementController(SQLiteKnowledgeManagementStore store,
                                         ProjectRegistry projectRegistry,
                                         ProjectAccessGuard accessGuard,
                                         KnowledgeBootstrapService bootstrapService,
                                         RetrievalPipeline retrievalPipeline,
                                         QdrantHybridStore qdrantStore,
                                         CodeQdrantStore codeStore,
                                         CodeIndexJobService codeIndexJobService,
                                         BusinessProjectCatalogService businessProjects) {
        this.store = store;
        this.projectRegistry = projectRegistry;
        this.accessGuard = accessGuard;
        this.bootstrapService = bootstrapService;
        this.retrievalPipeline = retrievalPipeline;
        this.qdrantStore = qdrantStore;
        this.codeStore = codeStore;
        this.codeIndexJobService = codeIndexJobService;
        this.businessProjects = businessProjects;
    }

    public KnowledgeManagementController(SQLiteKnowledgeManagementStore store,
                                         ProjectRegistry projectRegistry,
                                         ProjectAccessGuard accessGuard,
                                         KnowledgeBootstrapService bootstrapService,
                                         RetrievalPipeline retrievalPipeline,
                                         QdrantHybridStore qdrantStore,
                                         CodeQdrantStore codeStore,
                                         CodeIndexJobService codeIndexJobService) {
        this(store, projectRegistry, accessGuard, bootstrapService, retrievalPipeline,
                qdrantStore, codeStore, codeIndexJobService, null);
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
        if (businessProjects != null) {
            return listBusinessBases(accessibleBusinessProjects(projectId, request),
                    status, type, query, page, size);
        }
        List<RagProperties.ProjectConfig> projects = accessibleProjects(projectId, request);
        return listBasesWithFallback(projects, status, type, query, page, size);
    }

    private List<BusinessProject> accessibleBusinessProjects(String projectId, HttpServletRequest request) {
        UserContext user = accessGuard.currentUser(request);
        if (projectId != null && !projectId.isBlank()) {
            BusinessProject project = businessProjects.requireProject(projectId);
            if (!businessProjects.accessScopeIds(project.id()).stream().anyMatch(user::hasAccessTo)) {
                throw new AccessDeniedException("Insufficient permissions");
            }
            return List.of(project);
        }
        return businessProjects.projects().stream()
                .filter(project -> businessProjects.accessScopeIds(project.id()).stream().anyMatch(user::hasAccessTo))
                .toList();
    }

    private Page<KnowledgeBaseView> listBusinessBases(List<BusinessProject> projects,
                                                      SummaryStatus status, BaseType type,
                                                      String query, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size <= 0 ? 50 : size, 200));
        List<KnowledgeBaseView> values = new ArrayList<>();
        for (BusinessProject project : projects) {
            values.add(businessRequirementBase(project));
            for (CodeRepository repository : businessProjects.ownedRepositories(project.id())) {
                values.add(businessCodeBase(project, repository));
            }
            for (CodeRepository repository : businessProjects.sharedRepositories(project.id())) {
                values.add(businessCodeBase(project, repository));
            }
        }
        List<KnowledgeBaseView> filtered = values.stream()
                .filter(base -> status == null || base.status() == status)
                .filter(base -> type == null || base.type() == type)
                .filter(base -> matchesQuery(base, query))
                .toList();
        int from = (int) Math.min((long) safePage * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        return new Page<>(filtered.subList(from, to), safePage, safeSize, filtered.size());
    }

    private KnowledgeBaseView businessRequirementBase(BusinessProject project) {
        RequirementCount count = requirementCount(project);
        SummaryStatus status = count.available()
                ? count.points() > 0 ? SummaryStatus.READY : SummaryStatus.IDLE
                : SummaryStatus.UNAVAILABLE;
        return new KnowledgeBaseView(project.id() + ":requirement", project.id(),
                project.name() + " 需求", BaseType.REQUIREMENT, project.requirementCollection(),
                SourceType.ZIP, status, null, project.latestRequirementVersion(), 0, 0, 0,
                count.points(), null, null, null,
                project.requirementDocumentId(), project.latestRequirementVersion());
    }

    private KnowledgeBaseView businessCodeBase(BusinessProject project, CodeRepository repository) {
        CodeCount count = codeCount(repository);
        SummaryStatus status = !repository.enabled() ? SummaryStatus.DISABLED
                : !count.available() ? SummaryStatus.UNAVAILABLE
                : count.points() > 0 ? SummaryStatus.READY : SummaryStatus.IDLE;
        return new KnowledgeBaseView(project.id() + ":code:" + repository.id(), project.id(),
                repository.name(), BaseType.CODE, repository.codeCollection(), SourceType.GITLAB,
                status, null, null, 0, 0, 0, count.points(), null, null, null);
    }

    private RequirementCount requirementCount(BusinessProject project) {
        if (project.latestRequirementVersion() == null || project.latestRequirementVersion().isBlank()) {
            return new RequirementCount(true, 0);
        }
        try {
            return new RequirementCount(true, qdrantStore.countVersionIfAvailable(
                    project.requirementCollection(), project.requirementDocumentId(),
                    project.latestRequirementVersion()));
        } catch (RuntimeException exception) {
            return new RequirementCount(false, 0);
        }
    }

    private record RequirementCount(boolean available, long points) {}

    private CodeCount codeCount(CodeRepository repository) {
        try {
            long points = repository.liveAlias()
                    ? codeStore.countLiveProjectIfAvailable(repository.codeCollection(), repository.id())
                    : codeStore.countProjectIfAvailable(repository.codeCollection(), repository.id());
            return new CodeCount(true, points);
        } catch (RuntimeException exception) {
            return new CodeCount(false, 0);
        }
    }

    private record CodeCount(boolean available, long points) {}

    private List<RagProperties.ProjectConfig> accessibleProjects(String projectId,
                                                                 HttpServletRequest request) {
        if (projectId != null && !projectId.isBlank()) {
            projectRegistry.require(projectId);
            accessGuard.requireProjectAccess(request, projectId);
            return projectRegistry.find(projectId).stream().toList();
        }
        UserContext user = accessGuard.currentUser(request);
        return projectRegistry.all().stream()
                .filter(project -> project.id() != null && !project.id().isBlank())
                .filter(project -> user.hasAccessTo(project.id()))
                .toList();
    }

    private Page<KnowledgeBaseView> listBasesWithFallback(List<RagProperties.ProjectConfig> projects,
                                                          SummaryStatus status, BaseType type,
                                                          String query, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size <= 0 ? 50 : size, 200));
        List<String> projectIds = projects.stream()
                .map(RagProperties.ProjectConfig::id)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        List<KnowledgeBaseView> allDb = projectIds.isEmpty()
                ? List.of()
                : store.allBasesForProjects(projectIds);
        Set<String> dbBaseIds = new HashSet<>();
        for (KnowledgeBaseView base : allDb) {
            dbBaseIds.add(base.id());
        }
        List<KnowledgeBaseView> merged = new ArrayList<>(allDb);
        for (RagProperties.ProjectConfig project : projects) {
            for (BaseType candidateType : List.of(BaseType.REQUIREMENT, BaseType.CODE)) {
                String candidateId = project.id() + ":" + candidateType.name().toLowerCase();
                if (dbBaseIds.contains(candidateId)) continue;
                KnowledgeBaseView synthetic = syntheticBase(project, candidateType);
                if (synthetic != null) {
                    merged.add(synthetic);
                }
            }
        }
        List<KnowledgeBaseView> filtered = merged.stream()
                .filter(base -> status == null || base.status() == status)
                .filter(base -> type == null || base.type() == type)
                .filter(base -> matchesQuery(base, query))
                .toList();
        int from = (int) Math.min((long) safePage * safeSize, filtered.size());
        int to = Math.min(from + safeSize, filtered.size());
        return new Page<>(filtered.subList(from, to), safePage, safeSize, filtered.size());
    }

    private KnowledgeBaseView syntheticBase(RagProperties.ProjectConfig project, BaseType type) {
        String collection = collectionFor(project, type);
        if (collection == null || collection.isBlank()) return null;
        long points = scopedPointCount(project, collection, type);
        return points > 0 ? syntheticBase(project, collection, type, points) : null;
    }

    private long scopedPointCount(RagProperties.ProjectConfig project, String collection, BaseType type) {
        try {
            if (type == BaseType.CODE) {
                return codeStore.countProjectIfAvailable(collection, project.id());
            }
            RagProperties.ProjectKnowledge knowledge = project.knowledge();
            if (knowledge == null || knowledge.documentId() == null || knowledge.documentId().isBlank()
                    || knowledge.version() == null || knowledge.version().isBlank()) {
                return 0;
            }
            return qdrantStore.countVersionIfAvailable(
                    collection, knowledge.documentId(), knowledge.version());
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private KnowledgeBaseView syntheticBase(RagProperties.ProjectConfig project,
                                            String collection, BaseType type, long points) {
        String suffix = type == BaseType.CODE ? "code" : "requirement";
        SourceType source = type == BaseType.CODE ? SourceType.GITLAB : SourceType.ZIP;
        String version = type == BaseType.CODE ? null : (project.knowledge() == null ? null : project.knowledge().version());
        return new KnowledgeBaseView(
                project.id() + ":" + suffix,
                project.id(),
                project.name() == null || project.name().isBlank() ? project.id() : project.name(),
                type,
                collection,
                source,
                SummaryStatus.READY,
                null,
                version,
                0L,
                0L,
                0L,
                points,
                null,
                null,
                null);
    }

    private String collectionFor(RagProperties.ProjectConfig project, BaseType type) {
        return type == BaseType.CODE ? project.codeCollection() : project.requirementCollection();
    }

    private BaseType typeFromBaseId(String id) {
        int index = id == null ? -1 : id.lastIndexOf(':');
        if (index < 0) return null;
        String suffix = id.substring(index + 1).toLowerCase();
        return switch (suffix) {
            case "code" -> BaseType.CODE;
            case "requirement" -> BaseType.REQUIREMENT;
            default -> null;
        };
    }

    private boolean matchesQuery(KnowledgeBaseView base, String query) {
        if (query == null || query.isBlank()) return true;
        String q = query.trim().toLowerCase(Locale.ROOT);
        return contains(base.name(), q) || contains(base.projectId(), q) || contains(base.collection(), q);
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private String projectIdFromBaseId(String id) {
        int index = id == null ? -1 : id.lastIndexOf(':');
        return index > 0 ? id.substring(0, index) : id;
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
        if (base.type() == BaseType.CODE) {
            codeIndexJobService.start(repositoryIdFromBase(base));
            return accepted("CODE_INDEX_REBUILD", base.projectId());
        }
        if (businessProjects == null) {
            bootstrapService.bootstrapAsync(base.projectId());
        } else {
            bootstrapService.bootstrapAsync(
                    businessProjects.requireProject(base.projectId()).versionAnchorRepositoryId(),
                    base.projectId());
        }
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
        bootstrapRequirement(base);
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
        bootstrapRequirement(base);
        return accepted("DOCUMENT_REBUILD", base.projectId());
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @PostMapping("/{id}/retrieval-tests")
    public RetrievalTestResponse testRetrieval(@PathVariable String id,
                                               @Valid @RequestBody RetrievalTestRequest body,
                                               HttpServletRequest request) {
        KnowledgeBaseView base = requireBase(id, request);
        boolean codeBase = base.type() == BaseType.CODE;
        RetrievalProfile profile = codeBase
                ? RetrievalProfile.CODE_RETRIEVAL
                : RetrievalProfile.REQUIREMENT_REVIEW;
        RagOutcome<RetrievalBundle> outcome = retrievalPipeline.execute(new RetrievalRequest(
                body.query(), profile, base.projectId(),
                body.documentId(), body.version(), body.limit(), false, null,
                codeBase ? List.of(repositoryIdFromBase(base)) : List.of()));
        RetrievalBundle data = outcome.data();
        AtomicInteger rank = new AtomicInteger(1);
        List<RetrievalHit> hits = List.of();
        List<CodeHit> codeHits = List.of();
        if (codeBase) {
            List<CodeChunk> evidence = data == null ? List.of() : data.codeEvidence();
            codeHits = evidence.stream()
                    .map(chunk -> codeHit(rank.getAndIncrement(), chunk))
                    .toList();
        } else {
            List<ChunkRecord> evidence = data == null ? List.of() : data.requirementEvidence();
            hits = evidence.stream()
                    .map(chunk -> hit(rank.getAndIncrement(), chunk))
                    .toList();
        }
        return new RetrievalTestResponse(
                outcome.status(),
                data == null ? base.projectId() : data.resolvedProjectId(),
                data == null ? body.documentId() : data.documentId(),
                data == null ? body.version() : data.version(),
                hits,
                codeHits,
                outcome.warnings(),
                outcome.stageDiagnostics());
    }

    private KnowledgeBaseView requireBase(String id, HttpServletRequest request) {
        if (businessProjects != null) {
            KnowledgeBaseView businessBase = businessBase(id, request);
            if (businessBase != null) return businessBase;
        }
        try {
            KnowledgeBaseView base = store.requireBase(id);
            if (base == null) throw new IllegalArgumentException("knowledge base not found");
            projectRegistry.require(base.projectId());
            accessGuard.requireProjectAccess(request, base.projectId());
            return base;
        } catch (IllegalArgumentException exception) {
            String projectId = projectIdFromBaseId(id);
            BaseType baseType = typeFromBaseId(id);
            RagProperties.ProjectConfig project = projectRegistry.find(projectId).orElse(null);
            if (project != null && baseType != null) {
                accessGuard.requireProjectAccess(request, project.id());
                KnowledgeBaseView synthetic = syntheticBase(project, baseType);
                if (synthetic != null) return synthetic;
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识管理资源不存在");
        }
    }

    private KnowledgeBaseView businessBase(String id, HttpServletRequest request) {
        String[] parts = id == null ? new String[0] : id.split(":", 3);
        if (parts.length < 2) return null;
        BusinessProject project;
        try {
            project = businessProjects.requireProject(parts[0]);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        UserContext user = accessGuard.currentUser(request);
        if (!businessProjects.accessScopeIds(project.id()).stream().anyMatch(user::hasAccessTo)) {
            throw new AccessDeniedException("Insufficient permissions");
        }
        if ("requirement".equals(parts[1]) && parts.length == 2) {
            return businessRequirementBase(project);
        }
        if ("code".equals(parts[1]) && parts.length == 3) {
            return businessProjects.repositoryScope(project.id(), List.of(parts[2])).stream()
                    .findFirst().map(repository -> businessCodeBase(project, repository)).orElse(null);
        }
        return null;
    }

    private void bootstrapRequirement(KnowledgeBaseView base) {
        if (businessProjects == null) {
            bootstrapService.bootstrapAsync(base.projectId());
            return;
        }
        bootstrapService.bootstrapAsync(
                businessProjects.requireProject(base.projectId()).versionAnchorRepositoryId(),
                base.projectId());
    }

    private String repositoryIdFromBase(KnowledgeBaseView base) {
        String[] parts = base.id().split(":", 3);
        return parts.length == 3 && "code".equals(parts[1]) ? parts[2] : base.projectId();
    }

    private String bootstrapProjectId(String businessProjectId) {
        return businessProjects == null ? businessProjectId
                : businessProjects.requireProject(businessProjectId).versionAnchorRepositoryId();
    }

    private RetrievalHit hit(int rank, ChunkRecord chunk) {
        return new RetrievalHit(
                rank,
                chunk.id(),
                chunk.documentId(),
                chunk.version(),
                safeSourcePath(chunk.filename()),
                chunk.sectionPath(),
                chunk.heading(),
                chunk.requirementId(),
                chunk.module(),
                chunk.acceptanceCriteria(),
                chunk.parentId(),
                chunk.parentOrder(),
                chunk.childOrder(),
                chunk.contentHash(),
                truncate(chunk.childText(), CHILD_TEXT_LIMIT),
                truncate(chunk.parentText(), PARENT_TEXT_LIMIT));
    }

    private CodeHit codeHit(int rank, CodeChunk chunk) {
        return new CodeHit(
                rank,
                chunk.id(),
                chunk.projectId(),
                chunk.commitSha(),
                safeSourcePath(chunk.filePath()),
                chunk.symbolType(),
                chunk.symbolName(),
                chunk.startLine(),
                chunk.endLine(),
                truncate(chunk.text(), CODE_TEXT_LIMIT),
                chunk.contentHash(),
                chunk.language(),
                chunk.repositoryId(),
                chunk.repositoryName(),
                chunk.repositoryKind());
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
