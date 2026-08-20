package com.example.requirementrag.project;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.RagStageDiagnostic;
import com.example.requirementrag.model.RagWarning;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** 将业务项目代码查询展开到自有仓库与显式公共库，并按仓库排名轮转合并。 */
@Service
public class BusinessProjectCodeSearchService {

    private final BusinessProjectCatalogService catalog;
    private final CodeKnowledgeService codeKnowledgeService;

    public BusinessProjectCodeSearchService(BusinessProjectCatalogService catalog,
                                            CodeKnowledgeService codeKnowledgeService) {
        this.catalog = catalog;
        this.codeKnowledgeService = codeKnowledgeService;
    }

    public List<CodeChunk> search(String query, String projectId, List<String> repositoryIds, Integer limit) {
        return searchWithOutcome(query, projectId, repositoryIds, limit).data();
    }

    /** 扇出检索并保留失败仓库的可见降级状态。 */
    public RagOutcome<List<CodeChunk>> searchWithOutcome(String query, String projectId,
                                                         List<String> repositoryIds, Integer limit) {
        long started = System.nanoTime();
        int safeLimit = Math.min(Math.max(limit == null ? 8 : limit, 1), 50);
        String resolved = catalog.resolveProjectId(projectId);
        List<CodeRepository> repositories;
        try {
            repositories = catalog.repositoryScope(resolved, repositoryIds);
        } catch (IllegalArgumentException exception) {
            if (catalog.repository(projectId).isPresent()) {
                List<CodeChunk> data = codeKnowledgeService.search(query, projectId, safeLimit);
                return RagOutcome.of(data.isEmpty() ? RagOutcomeStatus.NO_RESULTS : RagOutcomeStatus.SUCCESS,
                        data, "code.hybrid_search", elapsed(started), data.size());
            }
            throw exception;
        }
        if (repositories.isEmpty()) {
            return RagOutcome.of(RagOutcomeStatus.NO_RESULTS, List.of(), "code.repository.search", elapsed(started), 0);
        }
        List<List<CodeChunk>> ranked = new ArrayList<>();
        List<String> failedRepositoryIds = new ArrayList<>();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = repositories.stream()
                    .map(repository -> executor.submit(() -> codeKnowledgeService.searchInCollection(
                            query, repository.id(), repository.liveAlias()
                                    ? repository.codeCollection() + "-live" : repository.codeCollection(),
                            safeLimit)))
                    .toList();
            for (int index = 0; index < futures.size(); index++) {
                try {
                    ranked.add(futures.get(index).get());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("多仓库代码检索被中断", exception);
                } catch (ExecutionException exception) {
                    failedRepositoryIds.add(repositories.get(index).id());
                }
            }
        }
        if (failedRepositoryIds.size() == repositories.size()) {
            throw new IllegalStateException("全部仓库代码检索失败");
        }
        List<CodeChunk> result = merge(repositoryIds, repositories, ranked, safeLimit);
        if (!failedRepositoryIds.isEmpty()) {
            String message = "部分代码仓库检索失败: " + String.join(", ", failedRepositoryIds);
            long duration = elapsed(started);
            return new RagOutcome<>(RagOutcomeStatus.DEGRADED, result,
                    List.of(new RagWarning("code.repository.search", "CODE_REPOSITORY_PARTIAL_FAILURE", message, duration)),
                    List.of(new RagStageDiagnostic("code.repository.search", RagOutcomeStatus.DEGRADED,
                            duration, result.size())));
        }
        return RagOutcome.of(result.isEmpty() ? RagOutcomeStatus.NO_RESULTS : RagOutcomeStatus.SUCCESS,
                result, "code.repository.search", elapsed(started), result.size());
    }

    private List<CodeChunk> merge(List<String> repositoryIds, List<CodeRepository> repositories,
                                  List<List<CodeChunk>> ranked, int safeLimit) {
        List<CodeChunk> merged = new ArrayList<>();
        for (int rank = 0; rank < safeLimit; rank++) {
            for (List<CodeChunk> hits : ranked) {
                if (rank < hits.size()) merged.add(hits.get(rank));
            }
        }
        List<CodeChunk> result = new ArrayList<>();
        for (int index = 0; index < merged.size() && result.size() < safeLimit; index++) {
            CodeChunk hit = merged.get(index);
            CodeRepository repository = repositories.stream()
                    .filter(candidate -> candidate.id().equals(hit.projectId()))
                    .findFirst().orElse(null);
            result.add(repository == null ? hit : hit.withRepositoryMetadata(repository.id(), repository.name(),
                    repository.kind().name()));
        }
        return result.stream().distinct().limit(safeLimit).toList();
    }

    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    public long count(String projectId) {
        return catalog.repositoryScope(projectId, List.of()).stream()
                .mapToLong(repository -> codeKnowledgeService.countInCollection(
                        repository.liveAlias() ? repository.codeCollection() + "-live" : repository.codeCollection(),
                        repository.id()))
                .sum();
    }
}
