package com.example.requirementrag.web;

import com.example.requirementrag.model.Permission;
import com.example.requirementrag.requirement.graph.RequirementGraphBuildService;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.BuildRequest;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchRequest;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchResponse;
import com.example.requirementrag.requirement.graph.RequirementGraphProperties;
import com.example.requirementrag.requirement.graph.RequirementGraphSearchService;
import com.example.requirementrag.requirement.graph.SQLiteRequirementGraphStore;
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

import java.util.List;

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

    public RequirementGraphController(RequirementGraphBuildService buildService,
                                      RequirementGraphSearchService searchService,
                                      SQLiteRequirementGraphStore store,
                                      ProjectAccessGuard accessGuard,
                                      RequirementGraphProperties properties) {
        this.buildService = buildService;
        this.searchService = searchService;
        this.store = store;
        this.accessGuard = accessGuard;
        this.properties = properties;
    }

    @PostMapping("/build")
    @RequiresPermission(Permission.WRITE)
    public GraphSnapshot build(@Valid @RequestBody BuildRequest request, HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return buildService.build(request);
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
        return searchService.search(request);
    }

    @PostMapping("/{snapshotId}/publish")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(Permission.WRITE)
    public GraphSnapshot publish(@PathVariable String snapshotId, HttpServletRequest httpRequest) {
        GraphSnapshot snapshot = store.requireSnapshot(snapshotId);
        accessGuard.requireProjectAccess(httpRequest, snapshot.businessProjectId());
        if (snapshot.status() != com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus.REVIEW_REQUIRED
                && snapshot.status() != com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus.VERIFIED) {
            throw new IllegalArgumentException("只有审核中的需求语义图可以发布");
        }
        store.updateStatus(snapshotId,
                com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus.PUBLISHED,
                null);
        return store.requireSnapshot(snapshotId);
    }
}
