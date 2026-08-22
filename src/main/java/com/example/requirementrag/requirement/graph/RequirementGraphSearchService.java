package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.model.RagWarning;
import io.micrometer.core.instrument.MeterRegistry;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.project.BusinessProjectCatalogService;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ClaimStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Entity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Evidence;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphPath;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.NeighborhoodResponse;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.PathResponse;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Relation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchMode;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchRequest;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchResponse;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Legacy lexical graph search with version, claim-status and evidence-resolution contracts. */
@Service
@ConditionalOnProperty(prefix = "app.rag.requirement-graph", name = "enabled", havingValue = "true", matchIfMissing = false)
public class RequirementGraphSearchService {
    private final SQLiteRequirementGraphStore store;
    private final QdrantHybridStore qdrantStore;
    private final ProjectRegistry projectRegistry;
    private final BusinessProjectCatalogService businessProjects;
    private final RequirementGraphProperties properties;
    private final RequirementGraphObservability observability;

    @Autowired
    public RequirementGraphSearchService(SQLiteRequirementGraphStore store, QdrantHybridStore qdrantStore,
                                         ProjectRegistry projectRegistry,
                                         ObjectProvider<BusinessProjectCatalogService> businessProjects,
                                         RequirementGraphProperties properties,
                                         ObjectProvider<MeterRegistry> meterRegistry) {
        this(store, qdrantStore, projectRegistry, businessProjects.getIfAvailable(), properties,
                new RequirementGraphObservability(meterRegistry.getIfAvailable()));
    }

    /** Compatibility constructor for tests without the business-project catalog. */
    public RequirementGraphSearchService(SQLiteRequirementGraphStore store, QdrantHybridStore qdrantStore,
                                         ProjectRegistry projectRegistry, RequirementGraphProperties properties) {
        this(store, qdrantStore, projectRegistry, (BusinessProjectCatalogService) null, properties,
                new RequirementGraphObservability(null));
    }

    private RequirementGraphSearchService(SQLiteRequirementGraphStore store, QdrantHybridStore qdrantStore,
                                          ProjectRegistry projectRegistry, BusinessProjectCatalogService businessProjects,
                                          RequirementGraphProperties properties,
                                          RequirementGraphObservability observability) {
        this.store = store;
        this.qdrantStore = qdrantStore;
        this.projectRegistry = projectRegistry;
        this.businessProjects = businessProjects;
        this.properties = properties;
        this.observability = observability == null ? new RequirementGraphObservability(null) : observability;
    }

    public SearchResponse search(SearchRequest request) {
        validate(request);
        long startedAt = System.nanoTime();
        if (!properties.retrievalEnabled()) {
            throw new RequirementGraphException("GRAPH_MODEL_UNAVAILABLE", "需求语义图检索未启用");
        }
        String projectId = resolveProjectId(request.projectId());
        GraphSnapshot snapshot = store.findLatest(projectId, request.documentId(), request.requirementVersion())
                .orElseThrow(() -> new RequirementGraphException("GRAPH_INPUT_EMPTY", "指定需求版本没有可用语义图"));
        if (properties.requirePublishedForSearch()
                && snapshot.status() != RequirementGraphModels.SnapshotStatus.PUBLISHED
                && snapshot.status() != RequirementGraphModels.SnapshotStatus.VERIFIED
                && !Boolean.TRUE.equals(request.includeUnresolved())) {
            throw new RequirementGraphException("GRAPH_SNAPSHOT_STALE", "需求语义图尚未发布或审核完成");
        }
        int limit = Math.min(Math.max(request.limit() == null ? 20 : request.limit(), 1), 50);
        int page = Math.max(0, request.page() == null ? 0 : request.page());
        Set<ClaimStatus> statuses = statuses(request, snapshot.schemaVersion());
        List<Entity> allEntities = store.allEntities(snapshot.id(), properties.maxGraphRows());
        Map<String, Entity> byId = new LinkedHashMap<>();
        allEntities.stream().filter(entity -> allowed(entity.claimStatus(), statuses, request.includeUnresolved()))
                .forEach(entity -> byId.put(entity.id(), entity));
        List<Entity> candidates = store.entities(snapshot.id(), request.query(), null, properties.candidateLimit()).stream()
                .filter(entity -> byId.containsKey(entity.id())).toList();
        Set<String> candidateIds = candidates.stream().map(Entity::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Relation> allRelations = store.allRelations(snapshot.id(), properties.maxGraphRows());
        List<Relation> selectedRelations = request.mode() == SearchMode.GLOBAL
                ? globalRelations(request.query(), candidateIds, allRelations, statuses, request.includeUnresolved(), limit)
                : localRelations(candidateIds, allRelations, statuses, request.includeUnresolved(),
                Math.min(request.maxHops() == null ? properties.maxHops() : request.maxHops(), 4), limit);
        Set<String> selectedIds = new LinkedHashSet<>(candidateIds);
        selectedRelations.forEach(relation -> {
            selectedIds.add(relation.sourceEntityId());
            selectedIds.add(relation.targetEntityId());
        });
        List<Entity> entities = selectedIds.stream().map(byId::get).filter(java.util.Objects::nonNull)
                .skip((long) page * limit).limit(limit).toList();
        List<Relation> pageRelations = selectedRelations.stream().skip((long) page * limit).limit(limit).toList();
        Set<String> evidenceIds = new LinkedHashSet<>();
        pageRelations.forEach(relation -> evidenceIds.addAll(relation.sourceEvidenceIds()));
        entities.forEach(entity -> evidenceIds.addAll(entity.sourceEvidenceIds()));
        List<Evidence> evidence = resolveEvidence(projectId, request.documentId(), request.requirementVersion(), snapshot.id(), evidenceIds);
        List<RagWarning> warnings = new ArrayList<>();
        if (evidence.size() < evidenceIds.size()
                || evidence.stream().anyMatch(item -> item.resolutionStatus() != RequirementGraphModels.EvidenceResolutionStatus.RESOLVED)) {
            warnings.add(new RagWarning("requirement.graph.evidence", "GRAPH_EVIDENCE_UNAVAILABLE", "部分图谱证据无法回查原文", 0));
        }
        boolean truncated = allEntities.size() >= properties.maxGraphRows() || allRelations.size() >= properties.maxGraphRows();
        if (truncated) warnings.add(new RagWarning("requirement.graph.search", "GRAPH_RESULT_TRUNCATED", "需求语义图结果已按上限截断", 0));
        String resultStatus = warnings.isEmpty() ? "success" : "degraded";
        observability.count("nexus.requirement_graph.search.completed", projectId, resultStatus);
        observability.timer("nexus.requirement_graph.search.duration", projectId, resultStatus,
                java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
        observability.value("nexus.requirement_graph.search.results", projectId, resultStatus,
                selectedIds.size() + selectedRelations.size());
        return new SearchResponse(snapshot, entities, pageRelations, evidence, warnings,
                selectedIds.size() + selectedRelations.size(), truncated, page, limit);
    }

    public SearchResponse searchMIX(SearchRequest request) {
        validate(request);
        long startedAt = System.nanoTime();
        if (!properties.retrievalEnabled()) {
            throw new RequirementGraphException("GRAPH_MODEL_UNAVAILABLE", "需求语义图检索未启用");
        }
        String projectId = resolveProjectId(request.projectId());
        GraphSnapshot snapshot = store.findLatest(projectId, request.documentId(), request.requirementVersion())
                .orElseThrow(() -> new RequirementGraphException("GRAPH_INPUT_EMPTY", "指定需求版本没有可用语义图"));
        if (properties.requirePublishedForSearch()
                && snapshot.status() != RequirementGraphModels.SnapshotStatus.PUBLISHED
                && snapshot.status() != RequirementGraphModels.SnapshotStatus.VERIFIED
                && !Boolean.TRUE.equals(request.includeUnresolved())) {
            throw new RequirementGraphException("GRAPH_SNAPSHOT_STALE", "需求语义图尚未发布或审核完成");
        }
        int limit = Math.min(Math.max(request.limit() == null ? 20 : request.limit(), 1), 50);
        int page = Math.max(0, request.page() == null ? 0 : request.page());
        Set<ClaimStatus> statuses = statuses(request, snapshot.schemaVersion());
        List<Entity> allEntities = store.allEntities(snapshot.id(), properties.maxGraphRows());
        Map<String, Entity> byId = new LinkedHashMap<>();
        allEntities.stream().filter(entity -> allowed(entity.claimStatus(), statuses, request.includeUnresolved()))
                .forEach(entity -> byId.put(entity.id(), entity));
        List<Entity> candidates = store.entities(snapshot.id(), request.query(), null, properties.candidateLimit()).stream()
                .filter(entity -> byId.containsKey(entity.id())).toList();
        Set<String> candidateIds = candidates.stream().map(Entity::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Relation> allRelations = store.allRelations(snapshot.id(), properties.maxGraphRows());
        List<Relation> selectedRelations = globalRelations(request.query(), candidateIds, allRelations, statuses, request.includeUnresolved(), limit);
        Set<String> selectedIds = new LinkedHashSet<>(candidateIds);
        selectedRelations.forEach(relation -> {
            selectedIds.add(relation.sourceEntityId());
            selectedIds.add(relation.targetEntityId());
        });
        List<Entity> entities = selectedIds.stream().map(byId::get).filter(java.util.Objects::nonNull)
                .skip((long) page * limit).limit(limit).toList();
        List<Relation> pageRelations = selectedRelations.stream().skip((long) page * limit).limit(limit).toList();
        Set<String> evidenceIds = new LinkedHashSet<>();
        pageRelations.forEach(relation -> evidenceIds.addAll(relation.sourceEvidenceIds()));
        entities.forEach(entity -> evidenceIds.addAll(entity.sourceEvidenceIds()));
        List<Evidence> evidence = resolveEvidence(projectId, request.documentId(), request.requirementVersion(), snapshot.id(), evidenceIds);
        List<RagWarning> warnings = new ArrayList<>();
        if (evidence.size() < evidenceIds.size()
                || evidence.stream().anyMatch(item -> item.resolutionStatus() != RequirementGraphModels.EvidenceResolutionStatus.RESOLVED)) {
            warnings.add(new RagWarning("requirement.graph.evidence", "GRAPH_EVIDENCE_UNAVAILABLE", "部分图谱证据无法回查原文", 0));
        }
        boolean truncated = allEntities.size() >= properties.maxGraphRows() || allRelations.size() >= properties.maxGraphRows();
        if (truncated) warnings.add(new RagWarning("requirement.graph.search", "GRAPH_RESULT_TRUNCATED", "需求语义图结果已按上限截断", 0));
        String resultStatus = warnings.isEmpty() ? "success" : "degraded";
        observability.count("nexus.requirement_graph.search.completed", projectId, resultStatus);
        observability.timer("nexus.requirement_graph.search.duration", projectId, resultStatus,
                java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
        observability.value("nexus.requirement_graph.search.results", projectId, resultStatus,
                selectedIds.size() + selectedRelations.size());
        return new SearchResponse(snapshot, entities, pageRelations, evidence, warnings,
                selectedIds.size() + selectedRelations.size(), truncated, page, limit);
    }

    public NeighborhoodResponse neighborhood(String snapshotId, String entityId, int maxHops,
                                             int limit, boolean includeUnresolved) {
        GraphSnapshot snapshot = store.requireSnapshot(snapshotId);
        int boundedHops = Math.min(Math.max(maxHops, 0), 4);
        int boundedLimit = Math.min(Math.max(limit, 1), 200);
        Set<ClaimStatus> allowedStatuses = includeUnresolved ? Set.of(ClaimStatus.values()) : Set.of(ClaimStatus.VERIFIED);
        Map<String, Entity> entitiesById = new LinkedHashMap<>();
        store.allEntities(snapshotId, properties.maxGraphRows()).forEach(item -> entitiesById.put(item.id(), item));
        if (!entitiesById.containsKey(entityId)) {
            throw new RequirementGraphException("GRAPH_INPUT_EMPTY", "未知需求图实体: " + entityId);
        }
        List<Relation> allRelations = store.allRelations(snapshotId, properties.maxGraphRows());
        Set<String> selectedEntityIds = new LinkedHashSet<>();
        Set<String> frontier = new LinkedHashSet<>(Set.of(entityId));
        Set<String> relationIds = new LinkedHashSet<>();
        selectedEntityIds.add(entityId);
        for (int hop = 0; hop < boundedHops && relationIds.size() < boundedLimit; hop++) {
            Set<String> next = new LinkedHashSet<>();
            for (Relation relation : allRelations) {
                if (!allowed(relation.claimStatus(), allowedStatuses, includeUnresolved)
                        || relationIds.contains(relation.id())
                        || (!frontier.contains(relation.sourceEntityId()) && !frontier.contains(relation.targetEntityId()))) {
                    continue;
                }
                relationIds.add(relation.id());
                selectedEntityIds.add(relation.sourceEntityId());
                selectedEntityIds.add(relation.targetEntityId());
                next.add(relation.sourceEntityId());
                next.add(relation.targetEntityId());
                if (relationIds.size() >= boundedLimit) break;
            }
            frontier = next;
        }
        List<Entity> entities = selectedEntityIds.stream().map(entitiesById::get).filter(java.util.Objects::nonNull)
                .limit(boundedLimit).toList();
        List<Relation> relations = allRelations.stream().filter(item -> relationIds.contains(item.id())).limit(boundedLimit).toList();
        Set<String> evidenceIds = new LinkedHashSet<>();
        entities.forEach(item -> evidenceIds.addAll(item.sourceEvidenceIds()));
        relations.forEach(item -> evidenceIds.addAll(item.sourceEvidenceIds()));
        List<Evidence> evidence = resolveEvidence(snapshot.businessProjectId(), snapshot.documentId(),
                snapshot.requirementVersion(), snapshotId, evidenceIds);
        List<RagWarning> warnings = new ArrayList<>();
        boolean truncated = selectedEntityIds.size() > boundedLimit || relationIds.size() > boundedLimit
                || allRelations.size() >= properties.maxGraphRows();
        if (truncated) warnings.add(new RagWarning("requirement.graph.neighborhood", "GRAPH_RESULT_TRUNCATED", "邻域结果已按上限截断", 0));
        if (evidence.stream().anyMatch(item -> item.resolutionStatus() != RequirementGraphModels.EvidenceResolutionStatus.RESOLVED)) {
            warnings.add(new RagWarning("requirement.graph.evidence", "GRAPH_EVIDENCE_UNAVAILABLE", "部分图谱证据无法回查原文", 0));
        }
        return new NeighborhoodResponse(snapshot, entityId, entities, relations, evidence, warnings,
                selectedEntityIds.size() + relationIds.size(), truncated, boundedHops);
    }

    public PathResponse paths(String snapshotId, String fromEntityId, String toEntityId,
                              int maxHops, int limit, boolean includeUnresolved) {
        GraphSnapshot snapshot = store.requireSnapshot(snapshotId);
        int boundedHops = Math.min(Math.max(maxHops, 1), 4);
        int boundedLimit = Math.min(Math.max(limit, 1), 20);
        Map<String, Entity> entitiesById = new LinkedHashMap<>();
        store.allEntities(snapshotId, properties.maxGraphRows()).forEach(item -> entitiesById.put(item.id(), item));
        if (!entitiesById.containsKey(fromEntityId) || !entitiesById.containsKey(toEntityId)) {
            throw new RequirementGraphException("GRAPH_INPUT_EMPTY", "路径端点实体不存在");
        }
        Set<ClaimStatus> allowedStatuses = includeUnresolved ? Set.of(ClaimStatus.values()) : Set.of(ClaimStatus.VERIFIED);
        List<Relation> relations = store.allRelations(snapshotId, properties.maxGraphRows()).stream()
                .filter(item -> allowed(item.claimStatus(), allowedStatuses, includeUnresolved)).toList();
        Map<String, List<Relation>> adjacency = new LinkedHashMap<>();
        for (Relation relation : relations) {
            adjacency.computeIfAbsent(relation.sourceEntityId(), ignored -> new ArrayList<>()).add(relation);
            adjacency.computeIfAbsent(relation.targetEntityId(), ignored -> new ArrayList<>()).add(relation);
        }
        ArrayDeque<PathState> queue = new ArrayDeque<>();
        queue.add(new PathState(fromEntityId, List.of(fromEntityId), List.of(), new LinkedHashSet<>(Set.of(fromEntityId))));
        List<GraphPath> paths = new ArrayList<>();
        while (!queue.isEmpty() && paths.size() < boundedLimit) {
            PathState state = queue.removeFirst();
            if (state.entityId().equals(toEntityId)) {
                paths.add(new GraphPath(state.entityIds(), state.relationIds(), state.relationIds().size(),
                        1.0 / Math.max(1, state.relationIds().size())));
                continue;
            }
            if (state.relationIds().size() >= boundedHops) continue;
            for (Relation relation : adjacency.getOrDefault(state.entityId(), List.of())) {
                String next = relation.sourceEntityId().equals(state.entityId())
                        ? relation.targetEntityId() : relation.sourceEntityId();
                if (state.seen().contains(next)) continue;
                Set<String> seen = new LinkedHashSet<>(state.seen());
                seen.add(next);
                List<String> entityIds = new ArrayList<>(state.entityIds());
                entityIds.add(next);
                List<String> relationIds = new ArrayList<>(state.relationIds());
                relationIds.add(relation.id());
                queue.addLast(new PathState(next, List.copyOf(entityIds), List.copyOf(relationIds), seen));
            }
        }
        Set<String> entityIds = paths.stream().flatMap(item -> item.entityIds().stream()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> relationIds = paths.stream().flatMap(item -> item.relationIds().stream()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Entity> entities = entityIds.stream().map(entitiesById::get).filter(java.util.Objects::nonNull).toList();
        List<Relation> selectedRelations = relations.stream().filter(item -> relationIds.contains(item.id())).toList();
        Set<String> evidenceIds = new LinkedHashSet<>();
        entities.forEach(item -> evidenceIds.addAll(item.sourceEvidenceIds()));
        selectedRelations.forEach(item -> evidenceIds.addAll(item.sourceEvidenceIds()));
        List<Evidence> evidence = resolveEvidence(snapshot.businessProjectId(), snapshot.documentId(), snapshot.requirementVersion(), snapshotId, evidenceIds);
        List<RagWarning> warnings = new ArrayList<>();
        if (paths.size() >= boundedLimit || relations.size() >= properties.maxGraphRows()) {
            warnings.add(new RagWarning("requirement.graph.paths", "GRAPH_RESULT_TRUNCATED", "路径结果已按上限截断", 0));
        }
        if (evidence.stream().anyMatch(item -> item.resolutionStatus() != RequirementGraphModels.EvidenceResolutionStatus.RESOLVED)) {
            warnings.add(new RagWarning("requirement.graph.evidence", "GRAPH_EVIDENCE_UNAVAILABLE", "部分图谱证据无法回查原文", 0));
        }
        return new PathResponse(snapshot, paths, entities, selectedRelations, evidence, warnings, paths.size(), !warnings.isEmpty());
    }

    private record PathState(String entityId, List<String> entityIds, List<String> relationIds, Set<String> seen) {
    }

    private Set<ClaimStatus> statuses(SearchRequest request, int snapshotSchemaVersion) {
        if (request.statuses() != null && !request.statuses().isEmpty()) return Set.copyOf(request.statuses());
        if (snapshotSchemaVersion <= 1 || properties.schemaVersion() <= 1) return Set.of(ClaimStatus.values());
        return Set.of(ClaimStatus.VERIFIED);
    }

    private boolean allowed(ClaimStatus status, Set<ClaimStatus> statuses, Boolean includeUnresolved) {
        return Boolean.TRUE.equals(includeUnresolved) || status == null || statuses.contains(status);
    }

    private List<Relation> localRelations(Set<String> candidates, List<Relation> relations, Set<ClaimStatus> statuses,
                                          Boolean includeUnresolved, int maxHops, int limit) {
        if (candidates.isEmpty() || maxHops == 0) return List.of();
        Set<String> frontier = new LinkedHashSet<>(candidates);
        Set<String> seenRelations = new HashSet<>();
        List<Relation> result = new ArrayList<>();
        for (int hop = 0; hop < maxHops && result.size() < limit; hop++) {
            Set<String> next = new LinkedHashSet<>();
            for (Relation relation : relations) {
                if (!allowed(relation.claimStatus(), statuses, includeUnresolved) || !seenRelations.add(relation.id())) continue;
                if (!frontier.contains(relation.sourceEntityId()) && !frontier.contains(relation.targetEntityId())) continue;
                result.add(relation);
                next.add(relation.sourceEntityId());
                next.add(relation.targetEntityId());
                if (result.size() >= limit) break;
            }
            frontier = next;
        }
        return List.copyOf(result);
    }

    private List<Relation> globalRelations(String query, Set<String> candidateIds, List<Relation> relations,
                                           Set<ClaimStatus> statuses, Boolean includeUnresolved, int limit) {
        List<String> terms = List.of(query.toLowerCase(Locale.ROOT).split("\\s+"));
        return relations.stream()
                .filter(relation -> allowed(relation.claimStatus(), statuses, includeUnresolved))
                .filter(relation -> candidateIds.isEmpty() || candidateIds.contains(relation.sourceEntityId())
                        || candidateIds.contains(relation.targetEntityId()))
                .sorted((left, right) -> Integer.compare(score(right, terms), score(left, terms)))
                .limit(limit)
                .toList();
    }

    private int score(Relation relation, List<String> terms) {
        String value = (relation.statement() + " " + relation.condition() + " " + relation.scenario()).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) if (term.length() > 1 && value.contains(term)) score++;
        return score * 100 + (int) Math.round(relation.confidence() * 10);
    }

    private List<Evidence> resolveEvidence(String projectId, String documentId, String version, String snapshotId,
                                           Set<String> ids) {
        if (ids.isEmpty()) return List.of();
        List<Evidence> persisted = store.evidence(snapshotId, ids);
        Map<String, Evidence> resolved = new LinkedHashMap<>();
        persisted.forEach(item -> resolved.put(item.evidenceId(), item));
        if (resolved.size() == ids.size()) return List.copyOf(resolved.values());
        try {
            List<com.example.requirementrag.model.ChunkRecord> chunks = qdrantStore.scrollVersion(resolveCollection(projectId), documentId, version);
            for (var chunk : chunks) {
                String id = RequirementGraphEvidence.id(projectId, version, chunk);
                if (ids.contains(id)) resolved.putIfAbsent(id, new Evidence(id, chunk.filename(), chunk.parentId(), chunk.parentOrder(),
                        chunk.version(), RequirementGraphEvidence.excerpt(chunk.parentText(), 600), chunk.contentHash()));
            }
        } catch (RuntimeException ignored) {
            // 检索层故障不伪造 Evidence；仅返回已由快照表解析的部分。
        }
        return List.copyOf(resolved.values());
    }

    private String resolveProjectId(String projectId) { return businessProjects == null ? projectId : businessProjects.resolveProjectId(projectId); }

    private String resolveCollection(String projectId) {
        if (businessProjects != null) return businessProjects.requireProject(projectId).requirementCollection();
        return projectRegistry.resolveRequirementCollection(projectId);
    }

    private void validate(SearchRequest request) {
        if (request == null || blank(request.projectId()) || blank(request.documentId())
                || blank(request.requirementVersion()) || blank(request.query())) {
            throw new RequirementGraphException("GRAPH_INPUT_EMPTY", "需求语义图查询请求不完整");
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
