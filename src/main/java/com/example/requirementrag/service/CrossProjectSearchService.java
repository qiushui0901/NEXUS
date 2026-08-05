package com.example.requirementrag.service;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.CrossProjectSearchResult;
import com.example.requirementrag.model.ScoredChunk;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 跨项目需求分块并行检索：在多个项目的 requirement collection 中 fan-out 搜索并合并排序。
 */
@Service
public class CrossProjectSearchService {
    private static final Logger log = LoggerFactory.getLogger(CrossProjectSearchService.class);

    private final ProjectRegistry projectRegistry;
    private final QdrantHybridStore store;
    private final RagProperties properties;

    public CrossProjectSearchService(ProjectRegistry projectRegistry, QdrantHybridStore store,
                                     RagProperties properties) {
        this.projectRegistry = projectRegistry;
        this.store = store;
        this.properties = properties;
    }

    /** 等待全部项目检索完成的总超时秒数，超时后仅返回已完成的项目结果。 */
    private static final long SEARCH_TIMEOUT_SECONDS = 30;

    /**
     * 在候选项目列表中并行检索需求分块，按相关性合并后返回 topK 条结果。
     * 单个项目检索失败不影响其他项目。
     *
     * @param query                检索查询文本
     * @param candidateProjectIds  候选项目 ID 列表；为空时直接返回空列表
     * @param topK                 返回的合并结果条数，会被限制在 1-50 之间
     * @return 按分数降序排列的跨项目检索结果，最多 topK 条
     */
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
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Cross-project search was interrupted; returning only completed project results", exception);
        }
        catch (TimeoutException exception) {
            log.warn("Cross-project search exceeded {} seconds; returning only completed project results",
                    SEARCH_TIMEOUT_SECONDS, exception);
        }
        catch (ExecutionException exception) {
            log.warn("At least one cross-project search failed; returning successful project results", exception);
        }
        return futures.stream()
                .filter(f -> f.isDone() && !f.isCompletedExceptionally())
                .flatMap(f -> f.join().stream())
                .sorted(Comparator.comparingDouble(CrossProjectSearchResult::score).reversed())
                .limit(resolvedTopK)
                .toList();
    }

    /** 在单个项目中执行混合检索，缺失知识配置或检索失败时返回空列表。 */
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
        catch (RuntimeException exception) {
            log.warn("Requirement search failed for project {}; omitting that project from merged results",
                    projectId, exception);
            return List.of();
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
