package com.example.requirementrag.web;

import com.example.requirementrag.code.CodeIndexJobService;
import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.CodeGraphRequest;
import com.example.requirementrag.model.CodeGraphResponse;
import com.example.requirementrag.model.CodeIndexJobStatus;
import com.example.requirementrag.model.CodeIndexResponse;
import com.example.requirementrag.model.IncrementalCodeIndexResponse;
import com.example.requirementrag.code.IncrementalCodeIndexService;
import com.example.requirementrag.code.CodeIntelligenceService;
import com.example.requirementrag.model.CodeIntelligenceResponse;
import com.example.requirementrag.model.SymbolGraphRequest;
import com.example.requirementrag.model.ImpactAnalysisRequest;
import com.example.requirementrag.model.CodeSearchRequest;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.SourceSnippet;
import com.example.requirementrag.project.BusinessProjectCodeSearchService;
import com.example.requirementrag.project.BusinessProjectCatalogService;
import com.example.requirementrag.project.CodeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 代码知识 REST 接口：索引、搜索和源码片段读取。
 */
@RestController
@RequestMapping("/api/code")
public class CodeController {

    private final CodeKnowledgeService codeKnowledgeService;
    private final IncrementalCodeIndexService incrementalCodeIndexService;
    private final CodeIndexJobService codeIndexJobService;
    private final ProjectAccessGuard accessGuard;
    private final CodeIntelligenceService codeIntelligenceService;
    private final BusinessProjectCodeSearchService businessCodeSearch;
    private final BusinessProjectCatalogService businessCatalog;

    /** 注入代码知识服务。 */
    @Autowired
    public CodeController(CodeKnowledgeService codeKnowledgeService,
                          IncrementalCodeIndexService incrementalCodeIndexService,
                          CodeIndexJobService codeIndexJobService,
                          ProjectAccessGuard accessGuard,
                          CodeIntelligenceService codeIntelligenceService,
                          BusinessProjectCodeSearchService businessCodeSearch,
                          BusinessProjectCatalogService businessCatalog) {
        this.codeKnowledgeService = codeKnowledgeService;
        this.incrementalCodeIndexService = incrementalCodeIndexService;
        this.codeIndexJobService = codeIndexJobService;
        this.accessGuard = accessGuard;
        this.codeIntelligenceService = codeIntelligenceService;
        this.businessCodeSearch = businessCodeSearch;
        this.businessCatalog = businessCatalog;
    }

    public CodeController(CodeKnowledgeService codeKnowledgeService,
                          IncrementalCodeIndexService incrementalCodeIndexService,
                          CodeIndexJobService codeIndexJobService,
                          ProjectAccessGuard accessGuard,
                          CodeIntelligenceService codeIntelligenceService) {
        this(codeKnowledgeService, incrementalCodeIndexService, codeIndexJobService, accessGuard,
                codeIntelligenceService, null, null);
    }

    /** 扫描配置仓库中的受支持语言，并写入代码向量和静态符号图。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/index")
    public CodeIndexResponse index(@RequestParam(required = false) String projectId,
                                   HttpServletRequest httpRequest) throws IOException {
        accessGuard.requireProjectAccess(httpRequest, projectId);
        if (projectId != null && !projectId.isBlank()) {
            return codeKnowledgeService.index(projectId);
        }
        return codeKnowledgeService.index();
    }

    /** 启动后台完整索引，立即返回任务状态，避免浏览器长时间等待。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/index/start")
    public ResponseEntity<CodeIndexJobStatus> startIndex(@RequestParam(required = false) String projectId,
                                                          HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, projectId);
        return ResponseEntity.accepted().body(codeIndexJobService.start(projectId));
    }

    /** 查询后台完整索引任务状态。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/index/status")
    public CodeIndexJobStatus indexStatus(@RequestParam(required = false) String projectId,
                                          HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, projectId);
        return codeIndexJobService.status(projectId);
    }

    /** 按 Git commit 范围增量更新当前项目的版本化代码 collection。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/incremental-index")
    public IncrementalCodeIndexResponse incrementalIndex(
            @RequestParam String projectId,
            @RequestParam String oldSha,
            @RequestParam String newSha,
            HttpServletRequest httpRequest) throws IOException, InterruptedException {
        accessGuard.requireProjectAccess(httpRequest, projectId);
        return incrementalCodeIndexService.indexWithResult(projectId, oldSha, newSha);
    }

    /** 对已索引代码执行语义检索。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @PostMapping("/search")
    public List<CodeChunk> search(@Valid @RequestBody CodeSearchRequest request, HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return businessCodeSearch == null
                ? codeKnowledgeService.search(request.query(), request.projectId(), request.limit())
                : businessCodeSearch.search(request.query(), request.projectId(),
                request.repositoryIds(), request.limit());
    }

    /** 根据查询和视图返回代码图谱。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @PostMapping("/graph")
    public CodeGraphResponse graph(@Valid @RequestBody CodeGraphRequest request, HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        CodeRepository repository = repositoryForUnambiguousRequest(request.projectId(), request.repositoryId());
        if (repository != null) {
            return codeKnowledgeService.graphInCollection(request.query(), repository.id(),
                    repository.liveAlias() ? repository.codeCollection() + "-live" : repository.codeCollection(),
                    request.view(), request.limit(), request.crossSide());
        }
        return codeKnowledgeService.graph(request.query(), request.projectId(), request.view(), request.limit(),
                request.crossSide());
    }

    /** 遍历持久化的静态符号调用图。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @PostMapping("/graph/symbols")
    public CodeIntelligenceResponse symbolGraph(@Valid @RequestBody SymbolGraphRequest request,
                                                HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        CodeRepository repository = repositoryForUnambiguousRequest(request.projectId(), request.repositoryId());
        String scope = repository == null ? request.projectId() : repository.id();
        return codeIntelligenceService.graph(scope, request.symbol(), request.direction(),
                request.depth(), request.limit());
    }

    /** 分析符号或提交区间的影响范围，并给出显式的置信等级。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @PostMapping("/impact")
    public CodeIntelligenceResponse impact(@RequestBody ImpactAnalysisRequest request,
                                           HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        boolean symbol = request.symbol() != null && !request.symbol().isBlank();
        boolean commits = request.fromCommit() != null && !request.fromCommit().isBlank()
                && request.toCommit() != null && !request.toCommit().isBlank();
        if (symbol == commits) {
            throw new IllegalArgumentException("Select exactly one impact mode: symbol or fromCommit+toCommit");
        }
        CodeRepository repository = repositoryForUnambiguousRequest(request.projectId(), request.repositoryId());
        String scope = repository == null ? request.projectId() : repository.id();
        if (symbol) {
            return codeIntelligenceService.impactSymbol(scope, request.symbol(), request.depth(), request.limit());
        }
        if (repository != null) {
            return codeIntelligenceService.impactCommitsInRepository(repository.id(), repository.repositoryPath(),
                    request.fromCommit(), request.toCommit(), request.depth(), request.limit());
        }
        return codeIntelligenceService.impactCommits(scope, request.fromCommit(), request.toCommit(),
                request.depth(), request.limit());
    }

    /** 返回代码索引统计。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam(required = false) String projectId,
                                      HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, projectId);
        return Map.of("chunks", businessCodeSearch == null
                ? codeKnowledgeService.count(projectId) : businessCodeSearch.count(projectId));
    }

    /** 读取源码片段。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/source")
    public SourceSnippet source(@RequestParam(required = false) String projectId,
                                @RequestParam(required = false) String repositoryId,
                                @RequestParam String filePath,
                                @RequestParam(required = false) Integer startLine,
                                @RequestParam(required = false) Integer endLine,
                                HttpServletRequest httpRequest) throws IOException {
        accessGuard.requireProjectAccess(httpRequest, projectId);
        CodeRepository repository = repositoryForUnambiguousRequest(projectId, repositoryId);
        if (repository != null) {
            return codeKnowledgeService.sourceInRepository(repository.repositoryPath(), filePath, startLine, endLine);
        }
        return codeKnowledgeService.source(projectId, filePath, startLine, endLine);
    }

    private CodeRepository repositoryForUnambiguousRequest(String projectId, String repositoryId) {
        if (repositoryId != null && !repositoryId.isBlank()) {
            return repositoryFor(projectId, repositoryId);
        }
        if (businessCatalog == null) {
            return null;
        }
        List<CodeRepository> repositories = businessCatalog.repositoryScope(projectId, List.of());
        if (repositories.size() != 1) {
            throw new IllegalArgumentException("多仓库业务项目必须指定 repositoryId");
        }
        return repositories.getFirst();
    }

    private CodeRepository repositoryFor(String projectId, String repositoryId) {
        if (businessCatalog == null) {
            throw new IllegalArgumentException("repositoryId requires business project catalog");
        }
        return businessCatalog.repositoryScope(projectId, List.of(repositoryId)).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("仓库不属于当前业务项目"));
    }
}
