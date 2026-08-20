package com.example.requirementrag.retrieval.pipeline;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.project.BusinessProjectCodeSearchService;
import org.springframework.stereotype.Service;

import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.RagOutcome;

import java.util.List;

/**
 * 代码检索默认实现：优先通过 {@link BusinessProjectCodeSearchService} 按仓库扇出，
 * 否则回退到 {@link CodeKnowledgeService}，保持与既有 {@link RetrievalPipeline} 行为一致。
 */
@Service
public class CodeRetrievalServiceImpl implements CodeRetrievalService {

    private final CodeKnowledgeService codeKnowledgeService;
    private final BusinessProjectCodeSearchService businessCodeSearch;

    public CodeRetrievalServiceImpl(CodeKnowledgeService codeKnowledgeService,
                                    BusinessProjectCodeSearchService businessCodeSearch) {
        this.codeKnowledgeService = codeKnowledgeService;
        this.businessCodeSearch = businessCodeSearch;
    }

    @Override
    public List<CodeChunk> search(String query, String businessProjectId, List<String> repositoryIds, int limit) {
        if (businessCodeSearch != null) {
            return businessCodeSearch.search(query, businessProjectId, repositoryIds, limit);
        }
        return codeKnowledgeService.search(query, businessProjectId, limit);
    }

    @Override
    public List<CodeChunk> search(String query, String businessProjectId, int limit) {
        return businessCodeSearch == null
                ? codeKnowledgeService.search(query, businessProjectId, limit)
                : businessCodeSearch.search(query, businessProjectId, List.of(), limit);
    }

    @Override
    public RagOutcome<List<CodeChunk>> searchOutcome(String query, String businessProjectId,
                                                      List<String> repositoryIds, int limit) {
        return businessCodeSearch == null
                ? CodeRetrievalService.super.searchOutcome(query, businessProjectId, repositoryIds, limit)
                : businessCodeSearch.searchWithOutcome(query, businessProjectId, repositoryIds, limit);
    }
}
