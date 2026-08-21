package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.model.ScoredChunk;
import com.example.requirementrag.project.BusinessProjectCatalogService;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ClaimStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Entity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Evidence;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphPath;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.QueryPlan;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Relation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchMode;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchRequest;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchResponse;
import com.example.requirementrag.retrieval.EmbeddingBatcher;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lexical, optional vector, and bounded graph retrieval for reviewable graph claims.
 *
 * <p>统一入口：<code>NAIVE</code> 只召回原始文本块；<code>LOCAL/GLOBAL</code> 走图邻域/全局关系；
 * <code>HYBRID</code> 融合文本与图通道；<code>MIX</code> 做文本块、实体、关系、路径、证据的加权融合排序。
 */
@Service
@ConditionalOnProperty(prefix = "app.rag.requirement-graph", name = "enabled", havingValue = "true")
public class RequirementGraphHybridSearchService {
    private final SQLiteRequirementGraphStore store;
    private final QdrantHybridStore qdrantStore;
    private final EmbeddingBatcher embeddingBatcher;
    private final RequirementGraphProperties properties;
    private final RequirementGraphSearchService legacySearch;
    private final ProjectRegistry projectRegistry;
    private final BusinessProjectCatalogService businessProjects;
    private final RequirementGraphFusionProperties fusion;
    private final RequirementGraphObservability observability = new RequirementGraphObservability(null);

    @Autowired
    public RequirementGraphHybridSearchService(SQLiteRequirementGraphStore store, QdrantHybridStore qdrantStore,
                                               ObjectProvider<EmbeddingBatcher> embeddingBatcher,
                                               RequirementGraphProperties properties,
                                               RequirementGraphSearchService legacySearch,
                                               ObjectProvider<ProjectRegistry> projectRegistry,
                                               ObjectProvider<BusinessProjectCatalogService> businessProjects,
                                               ObjectProvider<RequirementGraphFusionProperties> fusion) {
        this(store, qdrantStore, embeddingBatcher.getIfAvailable(), properties, legacySearch,
                projectRegistry.getIfAvailable(), businessProjects.getIfAvailable(),
                fusion.getIfAvailable() == null ? RequirementGraphFusionProperties.defaults() : fusion.getIfAvailable());
    }

    /** Compatibility constructor for tests. */
    public RequirementGraphHybridSearchService(SQLiteRequirementGraphStore store, QdrantHybridStore qdrantStore,
                                               ObjectProvider<EmbeddingBatcher> embeddingBatcher,
                                               RequirementGraphProperties properties,
                                               RequirementGraphSearchService legacySearch) {
        this(store, qdrantStore, embeddingBatcher.getIfAvailable(), properties, legacySearch,
                null, null, RequirementGraphFusionProperties.defaults());
    }

    private RequirementGraphHybridSearchService(SQLiteRequirementGraphStore store, QdrantHybridStore qdrantStore,
                                                EmbeddingBatcher embeddingBatcher,
                                                RequirementGraphProperties properties,
                                                RequirementGraphSearchService legacySearch,
                                                ProjectRegistry projectRegistry,
                                                BusinessProjectCatalogService businessProjects,
                                                RequirementGraphFusionProperties fusion) {
        this.store = store;
        this.qdrantStore = qdrantStore;
        this.embeddingBatcher = embeddingBatcher;
        this.properties = properties;
        this.legacySearch = legacySearch;
        this.projectRegistry = projectRegistry;
        this.businessProjects = businessProjects;
        this.fusion = fusion == null ? RequirementGraphFusionProperties.defaults() : fusion;
    }

    public SearchResponse search(SearchRequest request) {
        return search(request, null);
    }

    /** 统一入口：NAIVE / LOCAL / GLOBAL / HYBRID / MIX 都由这里调度。 */
    public SearchResponse search(SearchRequest request, QueryPlan plan) {
        if (request == null) {
            throw new RequirementGraphException("GRAPH_INPUT_EMPTY", "需求语义图查询请求不完整");
        }
        SearchMode mode = request.mode();
        if (mode == null || mode == SearchMode.LOCAL || mode == SearchMode.GLOBAL) {
            return legacySearch.search(request);
        }
        if (mode == SearchMode.NAIVE) {
            return searchNaive(request);
        }
        if (mode == SearchMode.MIX) {
            return searchMIX(request, plan);
        }
        return hybrid(request);
    }

    /** NAIVE：只检索原始需求文本块，不进入语义图。 */
    private SearchResponse searchNaive(SearchRequest request) {
        validate(request);
        if (!properties.retrievalEnabled()) {
            throw new RequirementGraphException("GRAPH_MODEL_UNAVAILABLE", "需求语义图检索未启用");
        }
        String projectId = resolveProjectId(request.projectId());
        int limit = Math.min(Math.max(request.limit() == null ? 20 : request.limit(), 1), 50);
        int page = Math.max(0, request.page() == null ? 0 : request.page());
        TextRetrievalResult text = textHits(projectId, request);
        List<ChunkRecord> chunks = text.hits().stream().map(ScoredChunk::record).filter(Objects::nonNull).toList();
        int total = chunks.size();
        List<ChunkRecord> pageChunks = chunks.stream().skip((long) page * limit).limit(limit).toList();
        boolean truncated = total > (long) page * limit + limit;
        List<RagWarning> warnings = new ArrayList<>();
        if (text.warningCode() != null) {
            warnings.add(new RagWarning("requirement.graph.text", text.warningCode(), text.warningMessage(), 0));
        } else if (chunks.isEmpty()) {
            warnings.add(new RagWarning("requirement.graph.text", "GRAPH_TEXT_NO_HITS", "原始文本检索没有命中", 0));
        }
        if (truncated) {
            warnings.add(new RagWarning("requirement.graph.search", "GRAPH_RESULT_TRUNCATED", "原始文本检索结果已截断", 0));
        }
        GraphSnapshot snapshot = store.findLatest(projectId, request.documentId(), request.requirementVersion())
                .orElse(null);
        return new SearchResponse(snapshot, List.of(), List.of(), List.of(), warnings,
                total, truncated, page, limit, pageChunks, List.of(), null, Map.of());
    }

    /** HYBRID：文本向量/图实体邻域融合（历史实现，保持兼容）。 */
    private SearchResponse hybrid(SearchRequest request) {
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
        Set<ClaimStatus> statuses = new LinkedHashSet<>(request.statuses() == null || request.statuses().isEmpty()
                ? snapshot.schemaVersion() <= 1 ? List.of(ClaimStatus.values()) : List.of(ClaimStatus.VERIFIED) : request.statuses());
        List<Entity> lexical = store.entities(snapshot.id(), request.query(), null, properties.maxGraphRows());
        Map<String, Entity> allEntities = new LinkedHashMap<>();
        store.allEntities(snapshot.id(), properties.maxGraphRows()).forEach(item -> allEntities.put(item.id(), item));
        List<Relation> allRelations = store.allRelations(snapshot.id(), properties.maxGraphRows());
        Map<String, Double> scores = new HashMap<>();
        String normalizedQuery = request.query().toLowerCase(Locale.ROOT);
        for (Entity entity : allEntities.values()) {
            if (!allowed(entity.claimStatus(), statuses, request.includeUnresolved())) continue;
            double score = lexicalScore(entity, normalizedQuery);
            if (properties.hybridRetrievalEnabled() && embeddingBatcher != null) score += vectorScore(entity, request.query());
            scores.put(entity.id(), score);
        }
        Set<String> seeds = lexical.stream().filter(item -> scores.containsKey(item.id())).map(Entity::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (seeds.isEmpty() && !scores.isEmpty()) {
            seeds.add(scores.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey());
        }
        List<Relation> selectedRelations = boundedRelations(seeds, allRelations, request.maxHops(), limit, statuses,
                Boolean.TRUE.equals(request.includeUnresolved()));
        Set<String> selectedIds = new LinkedHashSet<>(seeds);
        selectedRelations.forEach(item -> { selectedIds.add(item.sourceEntityId()); selectedIds.add(item.targetEntityId()); });
        List<Entity> selectedEntities = selectedIds.stream().map(allEntities::get).filter(Objects::nonNull)
                .filter(item -> allowed(item.claimStatus(), statuses, request.includeUnresolved()))
                .sorted(Comparator.comparingDouble((Entity item) -> scores.getOrDefault(item.id(), 0.0)).reversed())
                .skip((long) page * limit).limit(limit).toList();
        List<Relation> pageRelations = selectedRelations.stream().skip((long) page * limit).limit(limit).toList();
        Set<String> evidenceIds = new LinkedHashSet<>();
        selectedEntities.forEach(item -> evidenceIds.addAll(item.sourceEvidenceIds()));
        pageRelations.forEach(item -> evidenceIds.addAll(item.sourceEvidenceIds()));
        List<Evidence> evidence = resolveEvidence(projectId, request, snapshot.id(), evidenceIds);
        List<RagWarning> warnings = new ArrayList<>();
        boolean truncated = selectedIds.size() > limit || allEntities.size() >= properties.maxGraphRows()
                || allRelations.size() >= properties.maxGraphRows();
        if (truncated) warnings.add(new RagWarning("requirement.graph.search", "GRAPH_RESULT_TRUNCATED", "需求语义图结果已按上限截断", 0));
        if (evidence.stream().anyMatch(item -> item.resolutionStatus() != RequirementGraphModels.EvidenceResolutionStatus.RESOLVED)) {
            warnings.add(new RagWarning("requirement.graph.evidence", "GRAPH_EVIDENCE_UNAVAILABLE", "部分图谱证据无法回查原文", 0));
        }
        int total = selectedEntities.size() + pageRelations.size();
        return new SearchResponse(snapshot, selectedEntities, pageRelations, evidence, warnings, total, truncated, page, limit);
    }

    /** MIX：文本块、实体、关系、路径、证据联合召回，可解释加权融合排序。 */
    public SearchResponse searchMIX(SearchRequest request, QueryPlan plan) {
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
        long offset = (long) page * limit;

        // ---- 执行计划驱动检索：优先消费 QueryPlan，避免 plan 与真实执行不一致 ----
        Set<ClaimStatus> statuses = plan != null && !plan.allowedStatuses().isEmpty()
                ? plan.allowedStatuses()
                : (request.statuses() == null || request.statuses().isEmpty()
                ? Set.of(ClaimStatus.VERIFIED) : Set.copyOf(request.statuses()));
        int hops = plan != null ? Math.min(Math.max(plan.maxHops(), 0), 4)
                : Math.min(Math.max(request.maxHops() == null ? properties.maxHops() : request.maxHops(), 0), 4);
        int entityLimit = plan != null ? Math.max(1, plan.maxEntities()) : limit;
        int relationLimit = plan != null ? Math.max(1, plan.maxRelations()) : limit;
        int evidenceLimit = plan != null ? Math.max(1, plan.maxEvidence()) : limit;
        String entityQuery = plan != null && !plan.entityKeywords().isEmpty()
                ? String.join(" ", plan.entityKeywords()) : request.query();
        String normalizedEntityQuery = entityQuery.toLowerCase(Locale.ROOT);
        String normalizedQuery = request.query().toLowerCase(Locale.ROOT);
        List<String> relationTerms = plan != null && !plan.relationKeywords().isEmpty()
                ? plan.relationKeywords() : List.of(normalizedQuery.split("\\s+"));
        List<String> sectionKeywords = plan == null ? List.of() : plan.sectionKeywords();

        // ---- 文本通道：Qdrant 稠密+稀疏 RRF 召回原始需求块 ----
        TextRetrievalResult text = textHits(projectId, request);
        List<ScoredChunk> scoredChunks = text.hits();
        double maxText = scoredChunks.stream().mapToDouble(ScoredChunk::score).max().orElse(0.0);
        List<ChunkRecord> allChunks = new ArrayList<>();
        Set<String> chunkIds = new HashSet<>();
        Map<String, Double> chunkScoreByParentKey = new LinkedHashMap<>();
        for (ScoredChunk scored : scoredChunks) {
            ChunkRecord chunk = scored.record();
            if (chunk == null) continue;
            double normalized = maxText > 0 ? scored.score() / maxText : 0.0;
            if (chunkIds.add(chunk.id())) allChunks.add(chunk);
            String parentKey = parentKey(chunk.filename(), chunk.parentId(), chunk.parentOrder(), chunk.contentHash());
            chunkScoreByParentKey.merge(parentKey, normalized, Math::max);
        }
        List<ChunkRecord> pageChunks = allChunks.stream().skip(offset).limit(limit).toList();

        // ---- 实体通道 ----
        Map<String, Entity> allById = new LinkedHashMap<>();
        store.allEntities(snapshot.id(), properties.maxGraphRows()).stream()
                .filter(entity -> allowed(entity.claimStatus(), statuses, request.includeUnresolved()))
                .forEach(entity -> allById.put(entity.id(), entity));
        List<Entity> entityCandidates = store.entities(snapshot.id(), entityQuery, null, properties.candidateLimit()).stream()
                .filter(entity -> allById.containsKey(entity.id())).toList();
        Map<String, Double> entityRaw = new LinkedHashMap<>();
        for (Entity entity : entityCandidates) {
            double score = lexicalScore(entity, normalizedEntityQuery);
            if (properties.hybridRetrievalEnabled() && embeddingBatcher != null) score += vectorScore(entity, request.query());
            entityRaw.put(entity.id(), Math.max(0, score));
        }
        double maxEntity = entityRaw.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

        // ---- 关系通道 ----
        List<Relation> allRelations = store.allRelations(snapshot.id(), properties.maxGraphRows()).stream()
                .filter(relation -> allowed(relation.claimStatus(), statuses, request.includeUnresolved())).toList();
        Map<String, Relation> relationById = new LinkedHashMap<>();
        Map<String, Double> relationRaw = new LinkedHashMap<>();
        for (Relation relation : allRelations) {
            relationById.put(relation.id(), relation);
            double score = relationScore(relation, relationTerms);
            if (entityRaw.containsKey(relation.sourceEntityId()) || entityRaw.containsKey(relation.targetEntityId())) {
                score = Math.max(score, 0.05);
            }
            if (score > 0) relationRaw.put(relation.id(), score);
        }
        double maxRelation = relationRaw.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

        // ---- 路径通道：从种子实体出发的一跳/多跳路径 ----
        Set<String> seedIds = entityCandidates.stream().map(Entity::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        int pathBudget = Math.max(limit * (page + 1), Math.max(entityLimit, relationLimit) * (page + 1));
        List<GraphPath> allPaths = pathsFromSeeds(seedIds, allRelations, statuses,
                Boolean.TRUE.equals(request.includeUnresolved()), hops, pathBudget);
        List<GraphPath> pagePaths = allPaths.stream().skip(offset).limit(limit).toList();
        double maxPath = allPaths.stream().mapToDouble(GraphPath::score).max().orElse(0.0);
        Map<String, Double> pathScoreByEntity = new LinkedHashMap<>();
        Map<String, Double> pathScoreByRelation = new LinkedHashMap<>();
        for (GraphPath path : allPaths) {
            for (String entityId : path.entityIds()) pathScoreByEntity.merge(entityId, path.score(), Math::max);
            for (String relationId : path.relationIds()) pathScoreByRelation.merge(relationId, path.score(), Math::max);
        }

        // ---- 证据通道 ----
        Set<String> eligibleEntityIds = new LinkedHashSet<>(entityRaw.keySet());
        relationRaw.keySet().forEach(id -> {
            Relation relation = relationById.get(id);
            if (relation != null) {
                eligibleEntityIds.add(relation.sourceEntityId());
                eligibleEntityIds.add(relation.targetEntityId());
            }
        });
        Set<String> evidenceIds = new LinkedHashSet<>();
        eligibleEntityIds.stream().map(allById::get).filter(Objects::nonNull)
                .forEach(entity -> evidenceIds.addAll(entity.sourceEvidenceIds()));
        relationRaw.keySet().stream().map(relationById::get).filter(Objects::nonNull)
                .forEach(relation -> evidenceIds.addAll(relation.sourceEvidenceIds()));
        for (ChunkRecord chunk : pageChunks) {
            evidenceIds.add(RequirementGraphEvidence.id(projectId, request.requirementVersion(), chunk));
        }
        List<Evidence> evidence = resolveEvidence(projectId, request, snapshot.id(), evidenceIds);
        Map<String, Double> evidenceRaw = new LinkedHashMap<>();
        for (Evidence item : evidence) {
            double score = lexicalText(item.quote() + " " + item.excerpt() + " " + item.filename(), normalizedQuery);
            if (sectionMatches(item.sectionPath(), sectionKeywords)) score += 0.2;
            evidenceRaw.put(item.evidenceId(), score);
        }
        double maxEvidence = evidenceRaw.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

        // 文本块命中 → 通过 parentKey 关联到同父块的 span Evidence，真正参与图声明排序。
        Map<String, Double> chunkScoreByEvidenceId = new LinkedHashMap<>();
        for (Evidence item : evidence) {
            String parentKey = parentKey(item.filename(), item.parentId(), item.parentOrder(), item.contentHash());
            Double score = chunkScoreByParentKey.get(parentKey);
            if (score != null) chunkScoreByEvidenceId.merge(item.evidenceId(), score, Math::max);
        }

        // ---- 可解释加权融合 ----
        double wText = fusion.normalized(fusion.textWeight());
        double wEntity = fusion.normalized(fusion.entityWeight());
        double wRelation = fusion.normalized(fusion.relationWeight());
        double wPath = fusion.normalized(fusion.pathWeight());
        double wEvidence = fusion.normalized(fusion.evidenceWeight());
        double wFreshness = fusion.normalized(fusion.freshnessWeight());

        Map<String, Double> entityFused = new LinkedHashMap<>();
        for (String entityId : eligibleEntityIds) {
            Entity entity = allById.get(entityId);
            if (entity == null) continue;
            double entityScore = maxEntity > 0 ? entityRaw.getOrDefault(entityId, 0.0) / maxEntity : 0.0;
            double textScore = maxChunkScore(entity.sourceEvidenceIds(), chunkScoreByEvidenceId);
            double pathScore = maxPath > 0 ? pathScoreByEntity.getOrDefault(entityId, 0.0) / maxPath : 0.0;
            double evidenceScore = avgEvidenceScore(entity.sourceEvidenceIds(), evidenceRaw, maxEvidence);
            double freshness = freshness(entity.reviewedAt(), snapshot);
            entityFused.put(entityId, wEntity * entityScore + wText * textScore + wPath * pathScore
                    + wEvidence * evidenceScore + wFreshness * freshness);
        }
        List<Entity> selectedEntities = entityFused.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey).map(allById::get).filter(Objects::nonNull)
                .skip(offset).limit(entityLimit).toList();

        Map<String, Double> relationFused = new LinkedHashMap<>();
        for (String relationId : relationRaw.keySet()) {
            Relation relation = relationById.get(relationId);
            if (relation == null) continue;
            double relationScore = maxRelation > 0 ? relationRaw.get(relationId) / maxRelation : 0.0;
            double textScore = maxChunkScore(relation.sourceEvidenceIds(), chunkScoreByEvidenceId);
            double pathScore = maxPath > 0 ? pathScoreByRelation.getOrDefault(relationId, 0.0) / maxPath : 0.0;
            double evidenceScore = avgEvidenceScore(relation.sourceEvidenceIds(), evidenceRaw, maxEvidence);
            double freshness = freshness(relation.reviewedAt(), snapshot);
            relationFused.put(relationId, wRelation * relationScore + wText * textScore + wPath * pathScore
                    + wEvidence * evidenceScore + wFreshness * freshness);
        }
        List<Relation> selectedRelations = relationFused.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey).map(relationById::get).filter(Objects::nonNull)
                .skip(offset).limit(relationLimit).toList();

        // ---- 最终证据只保留返回结果引用的条目，并按通道得分分页 ----
        Set<String> finalEvidenceIds = new LinkedHashSet<>();
        selectedEntities.forEach(entity -> finalEvidenceIds.addAll(entity.sourceEvidenceIds()));
        selectedRelations.forEach(relation -> finalEvidenceIds.addAll(relation.sourceEvidenceIds()));
        pagePaths.forEach(path -> path.relationIds().stream().map(relationById::get).filter(Objects::nonNull)
                .forEach(relation -> finalEvidenceIds.addAll(relation.sourceEvidenceIds())));
        pageChunks.forEach(chunk -> finalEvidenceIds.add(RequirementGraphEvidence.id(projectId, request.requirementVersion(), chunk)));
        List<Evidence> allFinalEvidence = resolveEvidence(projectId, request, snapshot.id(), finalEvidenceIds).stream()
                .sorted(Comparator.comparingDouble((Evidence item) -> evidenceRaw.getOrDefault(item.evidenceId(), 0.0)).reversed())
                .toList();
        List<Evidence> finalEvidence = allFinalEvidence.stream().skip(offset).limit(evidenceLimit).toList();

        int total = entityFused.size() + relationFused.size() + allPaths.size() + allChunks.size() + allFinalEvidence.size();
        boolean truncated = allById.size() >= properties.maxGraphRows() || allRelations.size() >= properties.maxGraphRows()
                || scoredChunks.size() > (long) page * limit + limit || entityFused.size() > offset + entityLimit
                || relationFused.size() > offset + relationLimit || allPaths.size() > offset + limit;
        List<RagWarning> warnings = new ArrayList<>();
        if (text.warningCode() != null) {
            warnings.add(new RagWarning("requirement.graph.text", text.warningCode(), text.warningMessage(), 0));
        } else if (scoredChunks.isEmpty()) {
            warnings.add(new RagWarning("requirement.graph.text", "GRAPH_TEXT_NO_HITS", "文本检索通道没有命中，仅返回图结果", 0));
        }
        if (truncated) warnings.add(new RagWarning("requirement.graph.search", "GRAPH_RESULT_TRUNCATED", "需求语义图 MIX 结果已按上限截断", 0));
        if (evidence.stream().anyMatch(item -> item.resolutionStatus() != RequirementGraphModels.EvidenceResolutionStatus.RESOLVED)) {
            warnings.add(new RagWarning("requirement.graph.evidence", "GRAPH_EVIDENCE_UNAVAILABLE", "部分图谱证据无法回查原文", 0));
        }

        Map<String, Double> channelScores = new LinkedHashMap<>();
        channelScores.put("text", wText);
        channelScores.put("entity", wEntity);
        channelScores.put("relation", wRelation);
        channelScores.put("path", wPath);
        channelScores.put("evidence", wEvidence);
        channelScores.put("freshness", wFreshness);

        String resultStatus = warnings.isEmpty() ? "success" : "degraded";
        observability.count("nexus.requirement_graph.search.completed", projectId, resultStatus);
        observability.timer("nexus.requirement_graph.search.duration", projectId, resultStatus,
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
        observability.value("nexus.requirement_graph.search.results", projectId, resultStatus,
                entityFused.size() + relationFused.size() + allPaths.size() + allChunks.size());

        return new SearchResponse(snapshot, selectedEntities, selectedRelations, finalEvidence, warnings,
                total, truncated, page, limit, pageChunks, pagePaths, plan, channelScores);
    }

    private List<Relation> boundedRelations(Set<String> seeds, List<Relation> relations, Integer maxHops,
                                             int limit, Set<ClaimStatus> statuses, boolean includeUnresolved) {
        int hops = Math.min(Math.max(maxHops == null ? properties.maxHops() : maxHops, 0), 4);
        Set<String> frontier = new LinkedHashSet<>(seeds);
        Set<String> seen = new HashSet<>();
        List<Relation> result = new ArrayList<>();
        for (int hop = 0; hop < hops && result.size() < limit; hop++) {
            Set<String> next = new LinkedHashSet<>();
            for (Relation relation : relations) {
                if (!allowed(relation.claimStatus(), statuses, includeUnresolved) || seen.contains(relation.id())) continue;
                if (!frontier.contains(relation.sourceEntityId()) && !frontier.contains(relation.targetEntityId())) continue;
                seen.add(relation.id()); result.add(relation); next.add(relation.sourceEntityId()); next.add(relation.targetEntityId());
                if (result.size() >= limit) break;
            }
            frontier = next;
        }
        return List.copyOf(result);
    }

    private TextRetrievalResult textHits(String projectId, SearchRequest request) {
        try {
            List<ScoredChunk> hits = qdrantStore.hybridSearchWithScores(resolveCollection(projectId), request.query(),
                    request.documentId(), request.requirementVersion());
            return TextRetrievalResult.ok(hits == null ? List.of() : hits);
        } catch (RuntimeException exception) {
            String lower = (String.valueOf(exception.getClass().getSimpleName()) + " "
                    + String.valueOf(exception.getMessage())).toLowerCase(Locale.ROOT);
            if (lower.contains("timeout") || lower.contains("timed out")) {
                return TextRetrievalResult.fail("GRAPH_TEXT_RETRIEVAL_TIMEOUT", "文本检索通道超时");
            }
            return TextRetrievalResult.fail("GRAPH_TEXT_RETRIEVAL_UNAVAILABLE", "文本检索通道不可用");
        }
    }

    /** 文本检索通道结果：保留“正常空命中”与“通道故障”的区分，避免把故障伪装成空结果。 */
    private record TextRetrievalResult(List<ScoredChunk> hits, String warningCode, String warningMessage) {
        static TextRetrievalResult ok(List<ScoredChunk> hits) {
            return new TextRetrievalResult(hits == null ? List.of() : hits, null, null);
        }

        static TextRetrievalResult fail(String code, String message) {
            return new TextRetrievalResult(List.of(), code, message);
        }
    }

    /** 父块级稳定键：用于把 Qdrant 命中的 Chunk 与同父块的 span Evidence 关联起来。 */
    private String parentKey(String filename, String parentId, int parentOrder, String contentHash) {
        return safe(filename) + "|" + safe(parentId) + "|" + parentOrder + "|" + safe(contentHash);
    }

    private boolean sectionMatches(String sectionPath, List<String> keywords) {
        if (sectionPath == null || sectionPath.isBlank() || keywords == null || keywords.isEmpty()) return false;
        String value = sectionPath.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) if (keyword.length() > 1 && value.contains(keyword.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private String safe(String value) { return value == null ? "" : value.trim(); }

    /** 从种子实体出发 BFS，收集一跳/多跳路径，按 1/hops 打分并去重。 */
    private List<GraphPath> pathsFromSeeds(Set<String> seeds, List<Relation> relations, Set<ClaimStatus> statuses,
                                           boolean includeUnresolved, int hops, int limit) {
        if (seeds == null || seeds.isEmpty() || hops <= 0 || limit <= 0) return List.of();
        Map<String, List<Relation>> adjacency = new LinkedHashMap<>();
        for (Relation relation : relations) {
            if (!allowed(relation.claimStatus(), statuses, includeUnresolved)) continue;
            adjacency.computeIfAbsent(relation.sourceEntityId(), ignored -> new ArrayList<>()).add(relation);
            adjacency.computeIfAbsent(relation.targetEntityId(), ignored -> new ArrayList<>()).add(relation);
        }
        List<GraphPath> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int expansions = 0;
        for (String seed : seeds) {
            if (result.size() >= limit) break;
            ArrayDeque<FusionPathState> queue = new ArrayDeque<>();
            queue.add(new FusionPathState(seed, List.of(seed), List.of(), new LinkedHashSet<>(Set.of(seed))));
            while (!queue.isEmpty() && result.size() < limit && expansions < 50_000) {
                FusionPathState state = queue.removeFirst();
                if (!state.relationIds().isEmpty()) {
                    String key = String.join(">", state.entityIds());
                    if (seen.add(key)) {
                        result.add(new GraphPath(state.entityIds(), state.relationIds(),
                                state.relationIds().size(), 1.0 / Math.max(1, state.relationIds().size())));
                        if (result.size() >= limit) break;
                    }
                }
                if (state.relationIds().size() >= hops) continue;
                for (Relation relation : adjacency.getOrDefault(state.entityId(), List.of())) {
                    expansions++;
                    String next = relation.sourceEntityId().equals(state.entityId())
                            ? relation.targetEntityId() : relation.sourceEntityId();
                    if (state.seen().contains(next)) continue;
                    Set<String> seenSet = new LinkedHashSet<>(state.seen());
                    seenSet.add(next);
                    List<String> entityIds = new ArrayList<>(state.entityIds());
                    entityIds.add(next);
                    List<String> relationIds = new ArrayList<>(state.relationIds());
                    relationIds.add(relation.id());
                    queue.addLast(new FusionPathState(next, List.copyOf(entityIds), List.copyOf(relationIds), seenSet));
                }
            }
        }
        return List.copyOf(result);
    }

    private record FusionPathState(String entityId, List<String> entityIds, List<String> relationIds, Set<String> seen) {
    }

    private double lexicalScore(Entity entity, String query) {
        String value = (entity.displayName() + " " + entity.canonicalName() + " " + String.join(" ", entity.aliases())).toLowerCase(Locale.ROOT);
        double score = value.contains(query) ? 1.0 : 0.0;
        for (String term : query.split("\\s+")) if (term.length() > 1 && value.contains(term)) score += 0.1;
        return score + entity.confidence() * 0.01;
    }

    private double relationScore(Relation relation, List<String> terms) {
        String value = (relation.statement() + " " + relation.condition() + " " + relation.scenario()).toLowerCase(Locale.ROOT);
        double score = 0;
        for (String term : terms) if (term.length() > 1 && value.contains(term)) score += 0.2;
        return score + relation.confidence() * 0.01;
    }

    private double lexicalText(String value, String normalizedQuery) {
        if (value == null || value.isBlank()) return 0;
        String text = value.toLowerCase(Locale.ROOT);
        double score = text.contains(normalizedQuery) ? 1.0 : 0.0;
        for (String term : normalizedQuery.split("\\s+")) if (term.length() > 1 && text.contains(term)) score += 0.1;
        return score;
    }

    private double maxChunkScore(List<String> evidenceIds, Map<String, Double> chunkScoreById) {
        double best = 0;
        for (String id : evidenceIds) best = Math.max(best, chunkScoreById.getOrDefault(id, 0.0));
        return best;
    }

    private double avgEvidenceScore(List<String> ids, Map<String, Double> evidenceRaw, double maxEvidence) {
        if (ids == null || ids.isEmpty()) return 0;
        double sum = 0;
        int count = 0;
        for (String id : ids) {
            double value = evidenceRaw.getOrDefault(id, 0.0);
            if (maxEvidence > 0) value /= maxEvidence;
            sum += value;
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    private double freshness(Instant reviewedAt, GraphSnapshot snapshot) {
        Instant base = reviewedAt != null ? reviewedAt : (snapshot == null ? null : snapshot.updatedAt());
        if (base == null) return 0.5;
        long hours = Math.abs(Duration.between(base, Instant.now()).toHours());
        if (hours <= 24 * 7) return 1.0;
        if (hours <= 24 * 30) return 0.75;
        return 0.5;
    }

    private double vectorScore(Entity entity, String query) {
        try {
            List<float[]> vectors = embeddingBatcher.embedAll(List.of(query, entity.displayName() + " " + entity.description()));
            if (vectors.size() < 2) return 0;
            return cosine(vectors.get(0), vectors.get(1)) * 0.5;
        } catch (RuntimeException exception) { return 0; }
    }

    private double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length) return 0;
        double dot = 0, a = 0, b = 0;
        for (int i = 0; i < left.length; i++) { dot += left[i] * right[i]; a += left[i] * left[i]; b += right[i] * right[i]; }
        return a == 0 || b == 0 ? 0 : dot / Math.sqrt(a * b);
    }

    private List<Evidence> resolveEvidence(String projectId, SearchRequest request, String snapshotId, Set<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Evidence> persisted = store.evidence(snapshotId, ids);
        if (persisted.size() == ids.size()) return persisted;
        try {
            String collection = resolveCollection(projectId);
            List<ChunkRecord> chunks = qdrantStore.scrollVersion(collection, request.documentId(), request.requirementVersion());
            Map<String, Evidence> result = new LinkedHashMap<>();
            persisted.forEach(item -> result.put(item.evidenceId(), item));
            for (var chunk : chunks) {
                String id = RequirementGraphEvidence.id(projectId, request.requirementVersion(), chunk);
                if (ids.contains(id)) result.putIfAbsent(id, new Evidence(id, chunk.filename(), chunk.parentId(), chunk.parentOrder(), chunk.version(), RequirementGraphEvidence.excerpt(chunk.parentText(), 600), chunk.contentHash()));
            }
            return List.copyOf(result.values());
        } catch (RuntimeException exception) {
            return ids.stream().map(id -> new Evidence(id, "", "", 0, request.requirementVersion(), "", "")).toList();
        }
    }

    private boolean allowed(ClaimStatus status, Set<ClaimStatus> statuses, Boolean includeUnresolved) {
        return Boolean.TRUE.equals(includeUnresolved) || status == null || statuses.contains(status);
    }

    private String resolveProjectId(String projectId) {
        return businessProjects == null ? projectId : businessProjects.resolveProjectId(projectId);
    }

    private String resolveCollection(String projectId) {
        if (businessProjects != null) return businessProjects.requireProject(projectId).requirementCollection();
        if (projectRegistry != null) return projectRegistry.resolveRequirementCollection(projectId);
        return projectId;
    }

    private void validate(SearchRequest request) {
        if (request == null || blank(request.projectId()) || blank(request.documentId())
                || blank(request.requirementVersion()) || blank(request.query())) {
            throw new RequirementGraphException("GRAPH_INPUT_EMPTY", "需求语义图查询请求不完整");
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
