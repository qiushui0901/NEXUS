package com.example.requirementrag.service;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.CrossProjectSearchResult;
import com.example.requirementrag.model.ScoredChunk;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 跨项目需求分块并行检索：在多个项目的 requirement collection 中 fan-out 搜索并合并排序。
 */
@Service
public class CrossProjectSearchService {

    private final ProjectRegistry projectRegistry;
    private final QdrantHybridStore store;
    private final RagProperties properties;

    public CrossProjectSearchService(ProjectRegistry projectRegistry, QdrantHybridStore store,
                                     RagProperties properties) {
        this.projectRegistry = projectRegistry;
        this.store = store;
        this.properties = properties;
    }

    /**
     * 在候选项目列表中并行检索需求分块，按相关性合并后返回 topK 条结果。
     * 单个项目检索失败不影响其他项目。
     */
    private static final long SEARCH_TIMEOUT_SECONDS = 30;

    public List<CrossProjectSearchResult> fanOutSearch(String query, List<String> candidateProjectIds, int topK) {
        if (query == null || query.isBlank() || candidateProjectIds == null || candidateProjectIds.isEmpty()) {
            return List.of();
        }
        int resolvedTopK = Math.min(Math.max(topK, 1), 50);
        List<CompletableFuture<List<CrossProjectSearchResult>>> futures = candidateProjectIds.stream()
                .map(projectId -> CompletableFuture.supplyAsync(() -> searchProject(query, projectId, resolvedTopK)))
                .toList();
        CompletableFuture<Void> allDone = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        try {
            allDone.get(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        return futures.stream()
                .filter(f -> f.isDone() && !f.isCompletedExceptionally())
                .flatMap(f -> f.join().stream())
                .sorted(Comparator.comparingDouble(CrossProjectSearchResult::score).reversed())
                .limit(resolvedTopK)
                .toList();
    }

    private List<CrossProjectSearchResult> searchProject(String query, String projectId, int topK) {
        try {
            RagProperties.ProjectConfig project = projectRegistry.require(projectId);
            RagProperties.ProjectKnowledge knowledge = project.knowledge();
            if (knowledge == null) {
                return List.of();
            }
            String documentId = hasText(knowledge.documentId())
                    ? knowledge.documentId() : properties.knowledge().documentId();
            String version = hasText(knowledge.version())
                    ? knowledge.version() : properties.knowledge().version();
            if (!hasText(documentId) || !hasText(version)) {
                return List.of();
            }
            String collection = projectRegistry.resolveRequirementCollection(projectId);
            List<ScoredChunk> scored = store.hybridSearchWithScores(collection, query, documentId, version);
            String projectName = hasText(project.name()) ? project.name() : projectId;
            return scored.stream()
                    .limit(topK)
                    .map(sc -> new CrossProjectSearchResult(projectId, projectName, sc.record(), sc.score()))
                    .toList();
        }
        catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
