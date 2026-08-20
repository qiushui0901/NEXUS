package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.project.BusinessProjectCatalogService;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Entity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Evidence;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 需求语义图 Local/Global 查询；最终文本仍回查 Qdrant，不把图摘要当原文证据。 */
@Service
@ConditionalOnProperty(prefix = "app.rag.requirement-graph", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class RequirementGraphSearchService {
    private final SQLiteRequirementGraphStore store;
    private final QdrantHybridStore qdrantStore;
    private final ProjectRegistry projectRegistry;
    private final BusinessProjectCatalogService businessProjects;
    private final RequirementGraphProperties properties;

    @Autowired
    public RequirementGraphSearchService(SQLiteRequirementGraphStore store, QdrantHybridStore qdrantStore,
                                         ProjectRegistry projectRegistry,
                                         ObjectProvider<BusinessProjectCatalogService> businessProjects,
                                         RequirementGraphProperties properties) {
        this(store, qdrantStore, projectRegistry, businessProjects.getIfAvailable(), properties);
    }

    /** 供没有业务项目目录的兼容测试使用。 */
    public RequirementGraphSearchService(SQLiteRequirementGraphStore store, QdrantHybridStore qdrantStore,
                                         ProjectRegistry projectRegistry, RequirementGraphProperties properties) {
        this(store, qdrantStore, projectRegistry, (BusinessProjectCatalogService) null, properties);
    }

    private RequirementGraphSearchService(SQLiteRequirementGraphStore store, QdrantHybridStore qdrantStore,
                                          ProjectRegistry projectRegistry,
                                          BusinessProjectCatalogService businessProjects,
                                          RequirementGraphProperties properties) {
        this.store = store;
        this.qdrantStore = qdrantStore;
        this.projectRegistry = projectRegistry;
        this.businessProjects = businessProjects;
        this.properties = properties;
    }

    public SearchResponse search(SearchRequest request) {
        validate(request);
        if (!properties.retrievalEnabled()) {
            throw new IllegalStateException("需求语义图检索未启用");
        }
        String projectId = resolveProjectId(request.projectId());
        GraphSnapshot snapshot = store.findLatest(projectId, request.documentId(), request.requirementVersion())
                .orElseThrow(() -> new IllegalArgumentException("指定需求版本没有可用语义图"));
        int limit = Math.min(Math.max(request.limit() == null ? 20 : request.limit(), 1), 50);
        int maxHops = Math.min(Math.max(request.maxHops() == null ? properties.maxHops() : request.maxHops(), 0), 4);
        List<Entity> candidates = store.entities(snapshot.id(), request.query(), null,
                Math.min(properties.candidateLimit(), 200));
        List<Entity> allEntities = store.allEntities(snapshot.id(), 10_000);
        Map<String, Entity> byId = new LinkedHashMap<>();
        allEntities.forEach(entity -> byId.put(entity.id(), entity));
        Set<String> candidateIds = candidates.stream().map(Entity::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Relation> allRelations = store.allRelations(snapshot.id(), 10_000);
        Set<String> selectedIds = new LinkedHashSet<>(candidateIds);
        List<Relation> selectedRelations = request.mode() == SearchMode.GLOBAL
                ? globalRelations(request.query(), candidateIds, allRelations, selectedIds, limit)
                : localRelations(candidateIds, allRelations, selectedIds, maxHops, limit);
        List<Entity> entities = selectedIds.stream().map(byId::get).filter(java.util.Objects::nonNull)
                .limit(limit).toList();
        List<Evidence> evidence = resolveEvidence(projectId, request.documentId(), request.requirementVersion(),
                selectedRelations, entities);
        return new SearchResponse(snapshot, entities, selectedRelations, evidence);
    }

    private List<Relation> localRelations(Set<String> candidates, List<Relation> relations,
                                          Set<String> selectedIds, int maxHops, int limit) {
        if (candidates.isEmpty() || maxHops == 0) return List.of();
        Set<String> frontier = new LinkedHashSet<>(candidates);
        Set<String> seenRelations = new HashSet<>();
        List<Relation> result = new ArrayList<>();
        for (int hop = 0; hop < maxHops && result.size() < limit; hop++) {
            Set<String> next = new LinkedHashSet<>();
            for (Relation relation : relations) {
                if (!frontier.contains(relation.sourceEntityId()) && !frontier.contains(relation.targetEntityId())) continue;
                if (!seenRelations.add(relation.id())) continue;
                result.add(relation);
                selectedIds.add(relation.sourceEntityId());
                selectedIds.add(relation.targetEntityId());
                next.add(relation.sourceEntityId());
                next.add(relation.targetEntityId());
                if (result.size() >= limit) break;
            }
            frontier = next;
            if (frontier.isEmpty()) break;
        }
        return List.copyOf(result);
    }

    private List<Relation> globalRelations(String query, Set<String> candidateIds,
                                           List<Relation> relations, Set<String> selectedIds, int limit) {
        String normalized = query.toLowerCase(Locale.ROOT);
        List<String> terms = List.of(normalized.split("\\s+"));
        List<Relation> result = relations.stream()
                .filter(relation -> candidateIds.isEmpty()
                        || candidateIds.contains(relation.sourceEntityId())
                        || candidateIds.contains(relation.targetEntityId()))
                .sorted((left, right) -> Integer.compare(score(right, terms), score(left, terms)))
                .limit(limit)
                .toList();
        result.forEach(relation -> {
            selectedIds.add(relation.sourceEntityId());
            selectedIds.add(relation.targetEntityId());
        });
        return result;
    }

    private int score(Relation relation, List<String> terms) {
        String value = relation.statement().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (term.length() > 1 && value.contains(term)) score++;
        }
        return score * 100 + (int) Math.round(relation.confidence() * 10);
    }

    private List<Evidence> resolveEvidence(String projectId, String documentId, String version,
                                           List<Relation> relations, List<Entity> entities) {
        Set<String> ids = new LinkedHashSet<>();
        relations.forEach(relation -> ids.addAll(relation.sourceEvidenceIds()));
        entities.forEach(entity -> ids.addAll(entity.sourceEvidenceIds()));
        if (ids.isEmpty()) return List.of();
        String collection = resolveCollection(projectId);
        try {
            List<ChunkRecord> chunks = qdrantStore.scrollVersion(collection, documentId, version);
            Map<String, Evidence> resolved = new LinkedHashMap<>();
            for (ChunkRecord chunk : chunks) {
                String evidenceId = RequirementGraphEvidence.id(projectId, version, chunk);
                if (!ids.contains(evidenceId)) continue;
                resolved.putIfAbsent(evidenceId, new Evidence(evidenceId, chunk.filename(), chunk.parentId(),
                        chunk.parentOrder(), chunk.version(), RequirementGraphEvidence.excerpt(chunk.parentText(), 600),
                        chunk.contentHash()));
            }
            return List.copyOf(resolved.values());
        } catch (RuntimeException ignored) {
            // 图查询仍可返回关系和稳定 evidenceId；正文回查失败不能伪装成成功正文。
            return ids.stream().map(id -> new Evidence(id, "", "", 0, version, "", "")).toList();
        }
    }

    private String resolveProjectId(String projectId) {
        return businessProjects == null ? projectId : businessProjects.resolveProjectId(projectId);
    }

    private String resolveCollection(String projectId) {
        if (businessProjects != null) return businessProjects.requireProject(projectId).requirementCollection();
        return projectRegistry.resolveRequirementCollection(projectId);
    }

    private void validate(SearchRequest request) {
        if (request == null || blank(request.projectId()) || blank(request.documentId())
                || blank(request.requirementVersion()) || blank(request.query())) {
            throw new IllegalArgumentException("需求语义图查询请求不完整");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
