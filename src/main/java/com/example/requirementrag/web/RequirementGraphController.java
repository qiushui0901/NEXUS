package com.example.requirementrag.web;

import com.example.requirementrag.model.Permission;
import com.example.requirementrag.requirement.graph.RequirementGraphBuildService;
import com.example.requirementrag.requirement.graph.RequirementGraphBuildJobService;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.BuildRequest;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchRequest;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchResponse;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ClaimPage;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ClaimStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ReviewAction;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ClaimPatch;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ClaimDecision;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.NeighborhoodResponse;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.PathResponse;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.AuditEntry;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.BuildJob;
import com.example.requirementrag.requirement.graph.RequirementGraphProperties;
import com.example.requirementrag.requirement.graph.RequirementGraphHybridSearchService;
import com.example.requirementrag.requirement.graph.RequirementGraphException;
import com.example.requirementrag.requirement.graph.RequirementGraphQueryPlanner;
import com.example.requirementrag.requirement.graph.RequirementGraphSearchService;
import com.example.requirementrag.requirement.graph.SQLiteRequirementGraphStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 需求语义图构建、查询和发布接口；默认关闭，不改变现有需求检索 API。 */
@RestController
@RequestMapping("/api/requirement-graphs")
@ConditionalOnProperty(prefix = "app.rag.requirement-graph", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class RequirementGraphController {
    private final RequirementGraphBuildService buildService;
    private final RequirementGraphSearchService searchService;
    private final SQLiteRequirementGraphStore store;
    private final ProjectAccessGuard accessGuard;
    private final RequirementGraphProperties properties;
    private final RequirementGraphHybridSearchService hybridSearchService;
    private final RequirementGraphBuildJobService buildJobService;
    private final RequirementGraphQueryPlanner queryPlanner;

    public RequirementGraphController(RequirementGraphBuildService buildService,
                                      RequirementGraphSearchService searchService,
                                      SQLiteRequirementGraphStore store,
                                      ProjectAccessGuard accessGuard,
                                      RequirementGraphProperties properties) {
        this(buildService, searchService, store, accessGuard, properties,
                (RequirementGraphHybridSearchService) null, (RequirementGraphBuildJobService) null,
                (RequirementGraphQueryPlanner) null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RequirementGraphController(RequirementGraphBuildService buildService,
                                      RequirementGraphSearchService searchService,
                                      SQLiteRequirementGraphStore store,
                                      ProjectAccessGuard accessGuard,
                                      RequirementGraphProperties properties,
                                      ObjectProvider<RequirementGraphHybridSearchService> hybridSearchService,
                                      ObjectProvider<RequirementGraphBuildJobService> buildJobService,
                                      ObjectProvider<RequirementGraphQueryPlanner> queryPlanner) {
        this(buildService, searchService, store, accessGuard, properties,
                hybridSearchService.getIfAvailable(), buildJobService.getIfAvailable(),
                queryPlanner.getIfAvailable());
    }

    private RequirementGraphController(RequirementGraphBuildService buildService,
                                       RequirementGraphSearchService searchService,
                                       SQLiteRequirementGraphStore store,
                                       ProjectAccessGuard accessGuard,
                                       RequirementGraphProperties properties,
                                       RequirementGraphHybridSearchService hybridSearchService,
                                       RequirementGraphBuildJobService buildJobService,
                                       RequirementGraphQueryPlanner queryPlanner) {
        this.buildService = buildService;
        this.searchService = searchService;
        this.store = store;
        this.accessGuard = accessGuard;
        this.properties = properties;
        this.hybridSearchService = hybridSearchService;
        this.buildJobService = buildJobService;
        this.queryPlanner = queryPlanner;
    }

    @PostMapping("/build")
    @RequiresPermission(Permission.WRITE)
    public GraphSnapshot build(@Valid @RequestBody BuildRequest request, HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return buildService.build(request);
    }

    @PostMapping("/builds")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(Permission.WRITE)
    public BuildJob startBuild(@Valid @RequestBody BuildRequest request, HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return requireBuildJobs().start(request);
    }

    @PostMapping("/builds/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(Permission.WRITE)
    public BuildJob resumeBuildFromSnapshot(@Valid @RequestBody BuildRequest request,
                                            HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        if (request.resumeSnapshotId() == null || request.resumeSnapshotId().isBlank()) {
            throw new RequirementGraphException("GRAPH_INPUT_EMPTY", "恢复构建必须提供 resumeSnapshotId");
        }
        return requireBuildJobs().start(request);
    }

    @GetMapping("/builds/{buildId}")
    @RequiresPermission(Permission.PUBLIC_READ)
    public BuildJob buildStatus(@PathVariable String buildId, HttpServletRequest httpRequest) {
        BuildJob job = requireBuildJobs().require(buildId);
        accessGuard.requireProjectAccess(httpRequest, job.projectId());
        return job;
    }

    @PostMapping("/builds/{buildId}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(Permission.WRITE)
    public BuildJob resumeBuild(@PathVariable String buildId, HttpServletRequest httpRequest) {
        BuildJob job = requireBuildJobs().require(buildId);
        accessGuard.requireProjectAccess(httpRequest, job.projectId());
        return requireBuildJobs().resume(buildId);
    }

    @PostMapping("/builds/{buildId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(Permission.WRITE)
    public BuildJob cancelBuild(@PathVariable String buildId, HttpServletRequest httpRequest) {
        BuildJob job = requireBuildJobs().require(buildId);
        accessGuard.requireProjectAccess(httpRequest, job.projectId());
        return requireBuildJobs().cancel(buildId);
    }

    private RequirementGraphBuildJobService requireBuildJobs() {
        if (buildJobService == null) {
            throw new RequirementGraphException("GRAPH_MODEL_UNAVAILABLE", "需求图异步构建未启用");
        }
        return buildJobService;
    }

    @GetMapping("/snapshots")
    @RequiresPermission(Permission.PUBLIC_READ)
    public List<GraphSnapshot> snapshots(@RequestParam String projectId,
                                         @RequestParam(required = false) String documentId,
                                         @RequestParam(required = false) String version,
                                         HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, projectId);
        return store.listSnapshots(projectId, documentId, version);
    }

    @GetMapping("/{snapshotId}")
    @RequiresPermission(Permission.PUBLIC_READ)
    public GraphSnapshot snapshot(@PathVariable String snapshotId, HttpServletRequest httpRequest) {
        GraphSnapshot snapshot = store.requireSnapshot(snapshotId);
        accessGuard.requireProjectAccess(httpRequest, snapshot.businessProjectId());
        return snapshot;
    }

    @PostMapping("/search")
    @RequiresPermission(Permission.PUBLIC_READ)
    public SearchResponse search(@Valid @RequestBody SearchRequest request, HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        com.example.requirementrag.requirement.graph.RequirementGraphModels.QueryPlan plan =
                queryPlanner == null ? null : queryPlanner.plan(request);
        SearchRequest plannedRequest = request;
        if (plan != null && plan.mode() != request.mode()) {
            plannedRequest = new SearchRequest(
                    request.projectId(), request.documentId(), request.requirementVersion(), request.query(),
                    plan.mode(), request.limit(), request.maxHops(),
                    plan.allowedStatuses().stream().toList(), request.includeUnresolved(), request.page());
        }
        SearchResponse response;
        if (hybridSearchService != null) {
            // 五种检索模式（NAIVE/LOCAL/GLOBAL/HYBRID/MIX）统一由混合检索服务入口调度。
            response = hybridSearchService.search(plannedRequest, plan);
        } else {
            response = searchService.search(plannedRequest);
        }
        if (plan != null && response.plan() == null) {
            response = new SearchResponse(
                    response.snapshot(), response.entities(), response.relations(), response.evidence(),
                    response.warnings(), response.total(), response.truncated(), response.page(), response.pageSize(),
                    response.sourceChunks(), response.paths(), plan, response.channelScores());
        }
        return response;
    }

    @PostMapping("/{snapshotId}/publish")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(Permission.WRITE)
    public GraphSnapshot publish(@PathVariable String snapshotId, HttpServletRequest httpRequest) {
        GraphSnapshot snapshot = store.requireSnapshot(snapshotId);
        accessGuard.requireProjectAccess(httpRequest, snapshot.businessProjectId());
        String actor = properties.schemaVersion() <= 1 ? null : accessGuard.currentUser(httpRequest).username();
        if (properties.schemaVersion() <= 1) {
            store.updateStatus(snapshotId, com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus.PUBLISHED, null);
            return store.requireSnapshot(snapshotId);
        }
        return store.publishSnapshot(snapshotId, actor, "通过需求图审核发布");
    }

    @GetMapping("/{snapshotId}/claims")
    @RequiresPermission(Permission.PUBLIC_READ)
    public ClaimPage claims(@PathVariable String snapshotId,
                            @RequestParam(required = false) ClaimStatus status,
                            @RequestParam(defaultValue = "50") int limit,
                            @RequestParam(defaultValue = "0") int offset,
                            HttpServletRequest httpRequest) {
        GraphSnapshot snapshot = store.requireSnapshot(snapshotId);
        accessGuard.requireProjectAccess(httpRequest, snapshot.businessProjectId());
        return store.claims(snapshotId, status, limit, offset);
    }

    @PostMapping("/claims/{claimId}/review")
    @RequiresPermission(Permission.WRITE)
    public Map<String, String> reviewClaim(@PathVariable String claimId,
                                           @Valid @RequestBody ReviewAction action,
                                           HttpServletRequest httpRequest) {
        String snapshotId = claimId.startsWith("entity:") ? store.snapshotIdForEntity(claimId) : store.snapshotIdForRelation(claimId);
        GraphSnapshot snapshot = store.requireSnapshot(snapshotId);
        accessGuard.requireProjectAccess(httpRequest, snapshot.businessProjectId());
        String actor = accessGuard.currentUser(httpRequest).username();
        ClaimStatus status = action.status() != null ? action.status()
                : ClaimStatus.valueOf(action.newType());
        store.reviewClaim(claimId, status, actor, action.reason());
        return Map.of("status", "accepted", "claimId", claimId);
    }

    @PostMapping("/claims/{claimId}/verify")
    @RequiresPermission(Permission.WRITE)
    public Map<String, String> verifyClaim(@PathVariable String claimId,
                                           @RequestBody(required = false) ClaimDecision decision,
                                           HttpServletRequest httpRequest) {
        return decideClaim(claimId, ClaimStatus.VERIFIED, decision == null ? null : decision.reason(), httpRequest);
    }

    @PostMapping("/claims/{claimId}/reject")
    @RequiresPermission(Permission.WRITE)
    public Map<String, String> rejectClaim(@PathVariable String claimId,
                                           @RequestBody(required = false) ClaimDecision decision,
                                           HttpServletRequest httpRequest) {
        return decideClaim(claimId, ClaimStatus.REJECTED, decision == null ? null : decision.reason(), httpRequest);
    }

    private Map<String, String> decideClaim(String claimId, ClaimStatus status, String reason,
                                            HttpServletRequest httpRequest) {
        String snapshotId = claimId.startsWith("entity:") ? store.snapshotIdForEntity(claimId) : store.snapshotIdForRelation(claimId);
        GraphSnapshot snapshot = store.requireSnapshot(snapshotId);
        accessGuard.requireProjectAccess(httpRequest, snapshot.businessProjectId());
        store.reviewClaim(claimId, status, accessGuard.currentUser(httpRequest).username(), reason);
        return Map.of("status", "accepted", "claimId", claimId, "claimStatus", status.name());
    }

    @PostMapping("/claims/{claimId}/merge")
    @RequiresPermission(Permission.WRITE)
    public Map<String, String> mergeClaim(@PathVariable String claimId,
                                          @Valid @RequestBody ReviewAction action,
                                          HttpServletRequest httpRequest) {
        String snapshotId = claimId.startsWith("entity:") ? store.snapshotIdForEntity(claimId) : store.snapshotIdForRelation(claimId);
        GraphSnapshot snapshot = store.requireSnapshot(snapshotId);
        accessGuard.requireProjectAccess(httpRequest, snapshot.businessProjectId());
        store.mergeClaim(claimId, action.targetClaimId(), accessGuard.currentUser(httpRequest).username(), action.reason());
        return Map.of("status", "accepted", "claimId", claimId, "targetClaimId", String.valueOf(action.targetClaimId()));
    }

    @PostMapping("/claims/{claimId}/split")
    @RequiresPermission(Permission.WRITE)
    public Map<String, String> splitClaim(@PathVariable String claimId,
                                          @Valid @RequestBody ReviewAction action,
                                          HttpServletRequest httpRequest) {
        String snapshotId = claimId.startsWith("entity:") ? store.snapshotIdForEntity(claimId) : store.snapshotIdForRelation(claimId);
        GraphSnapshot snapshot = store.requireSnapshot(snapshotId);
        accessGuard.requireProjectAccess(httpRequest, snapshot.businessProjectId());
        String actor = accessGuard.currentUser(httpRequest).username();
        String newId = claimId.startsWith("entity:")
                ? store.splitEntity(claimId, action.newName(), actor, action.reason())
                : store.splitRelation(claimId, action.newStatement(), action.newTargetEntityId(), action.newRelationType(), actor, action.reason());
        return Map.of("status", "accepted", "claimId", claimId, "newClaimId", newId);
    }

    @GetMapping("/{snapshotId}/neighborhood/{entityId}")
    @RequiresPermission(Permission.PUBLIC_READ)
    public NeighborhoodResponse neighborhood(@PathVariable String snapshotId,
                                             @PathVariable String entityId,
                                             @RequestParam(defaultValue = "2") int maxHops,
                                             @RequestParam(defaultValue = "50") int limit,
                                             @RequestParam(defaultValue = "false") boolean includeUnresolved,
                                             HttpServletRequest httpRequest) {
        GraphSnapshot snapshot = store.requireSnapshot(snapshotId);
        accessGuard.requireProjectAccess(httpRequest, snapshot.businessProjectId());
        return searchService.neighborhood(snapshotId, entityId, maxHops, limit, includeUnresolved);
    }

    @GetMapping("/{snapshotId}/paths")
    @RequiresPermission(Permission.PUBLIC_READ)
    public PathResponse paths(@PathVariable String snapshotId,
                              @RequestParam String fromEntityId,
                              @RequestParam String toEntityId,
                              @RequestParam(defaultValue = "3") int maxHops,
                              @RequestParam(defaultValue = "10") int limit,
                              @RequestParam(defaultValue = "false") boolean includeUnresolved,
                              HttpServletRequest httpRequest) {
        GraphSnapshot snapshot = store.requireSnapshot(snapshotId);
        accessGuard.requireProjectAccess(httpRequest, snapshot.businessProjectId());
        return searchService.paths(snapshotId, fromEntityId, toEntityId, maxHops, limit, includeUnresolved);
    }

    @PatchMapping("/entities/{entityId}")
    @RequiresPermission(Permission.WRITE)
    public Map<String, String> patchEntity(@PathVariable String entityId, @RequestBody ClaimPatch patch,
                                           HttpServletRequest httpRequest) {
        GraphSnapshot snapshot = store.requireSnapshot(store.snapshotIdForEntity(entityId));
        accessGuard.requireProjectAccess(httpRequest, snapshot.businessProjectId());
        store.patchEntity(entityId, patch.displayName(), patch.description(), accessGuard.currentUser(httpRequest).username(), patch.reason());
        return Map.of("status", "accepted", "entityId", entityId);
    }

    @PatchMapping("/relations/{relationId}")
    @RequiresPermission(Permission.WRITE)
    public Map<String, String> patchRelation(@PathVariable String relationId, @RequestBody ClaimPatch patch,
                                             HttpServletRequest httpRequest) {
        GraphSnapshot snapshot = store.requireSnapshot(store.snapshotIdForRelation(relationId));
        accessGuard.requireProjectAccess(httpRequest, snapshot.businessProjectId());
        store.patchRelation(relationId, patch.statement(), patch.condition(), patch.scenario(), accessGuard.currentUser(httpRequest).username(), patch.reason());
        return Map.of("status", "accepted", "relationId", relationId);
    }

    @GetMapping("/{snapshotId}/audit")
    @RequiresPermission(Permission.PUBLIC_READ)
    public List<AuditEntry> audit(@PathVariable String snapshotId, HttpServletRequest httpRequest) {
        GraphSnapshot snapshot = store.requireSnapshot(snapshotId);
        accessGuard.requireProjectAccess(httpRequest, snapshot.businessProjectId());
        return store.audits(snapshotId);
    }
}
