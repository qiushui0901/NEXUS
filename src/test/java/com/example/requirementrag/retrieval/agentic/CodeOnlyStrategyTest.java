package com.example.requirementrag.retrieval.agentic;

import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.project.BusinessProjectCatalogService;
import com.example.requirementrag.project.BusinessProjectCodeSearchService;
import com.example.requirementrag.project.CodeRepository;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeOnlyStrategyTest {

    @Test
    void searchesCodeWithoutRequirementPipeline() {
        BusinessProjectCatalogService catalog = mock(BusinessProjectCatalogService.class);
        BusinessProjectCodeSearchService business = mock(BusinessProjectCodeSearchService.class);
        when(catalog.resolveProjectId(any())).thenReturn("game");
        when(catalog.repositoryScope(eq("game"), any())).thenReturn(List.of(
                new CodeRepository("game", "game", CodeRepository.Kind.PROJECT, "game", "server",
                        "game_code", "/tmp", "group/game", "MAVEN_POM", "pom.xml", true, true, "now", "now")));
        CodeChunk chunk = new CodeChunk("c1", "game", "sha", "src/FeatureService.java",
                "method", "run", 10, 20, "void run() {}", "hash", "java");
        when(business.search(eq("怎么实现关注列表"), eq("game"), any(), eq(8))).thenReturn(List.of(chunk));

        CodeOnlyStrategy strategy = new CodeOnlyStrategy(catalog, business);
        StrategyResult result = strategy.execute(new RetrievalRequest("怎么实现关注列表",
                com.example.requirementrag.retrieval.pipeline.RetrievalProfile.DEVELOPMENT_PLAN,
                null, null, null, 8));

        verify(business).search(eq("怎么实现关注列表"), eq("game"), any(), eq(8));
        assertEquals("code", result.strategy());
        assertEquals(1, result.codeHitCount());
        assertEquals("game", result.bundle().resolvedProjectId());
    }

    @Test
    void reportsNoResultsWhenNothingHit() {
        BusinessProjectCatalogService catalog = mock(BusinessProjectCatalogService.class);
        BusinessProjectCodeSearchService business = mock(BusinessProjectCodeSearchService.class);
        when(catalog.resolveProjectId(eq("game"))).thenReturn("game");
        when(catalog.repositoryScope(eq("game"), any())).thenReturn(List.of(
                new CodeRepository("game", "game", CodeRepository.Kind.PROJECT, "game", "server",
                        "game_code", "/tmp", "group/game", "MAVEN_POM", "pom.xml", true, true, "now", "now")));
        when(business.search(eq("不存在的东西"), eq("game"), any(), eq(8))).thenReturn(List.of());

        CodeOnlyStrategy strategy = new CodeOnlyStrategy(catalog, business);
        StrategyResult result = strategy.execute(new RetrievalRequest("不存在的东西",
                com.example.requirementrag.retrieval.pipeline.RetrievalProfile.DEVELOPMENT_PLAN,
                "game", null, null, 8));

        assertEquals(com.example.requirementrag.model.RagOutcomeStatus.NO_RESULTS, result.status());
        assertTrue(result.bundle().codeEvidence().isEmpty());
    }
}
