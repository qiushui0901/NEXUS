package com.example.requirementrag.retrieval.agentic;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeOnlyStrategyTest {

    @Test
    void searchesCodeWithoutRequirementPipeline() {
        CodeKnowledgeService code = mock(CodeKnowledgeService.class);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        RagProperties.ProjectConfig project = mock(RagProperties.ProjectConfig.class);
        when(registry.defaultProject()).thenReturn(project);
        when(project.id()).thenReturn("game");
        CodeChunk chunk = new CodeChunk("c1", "game", "sha", "src/FeatureService.java",
                "method", "run", 10, 20, "void run() {}", "hash", "java");
        when(code.search("怎么实现关注列表", "game", 8)).thenReturn(List.of(chunk));

        CodeOnlyStrategy strategy = new CodeOnlyStrategy(code, registry);
        StrategyResult result = strategy.execute(new RetrievalRequest("怎么实现关注列表",
                com.example.requirementrag.retrieval.pipeline.RetrievalProfile.DEVELOPMENT_PLAN,
                null, null, null, 8));

        verify(code).search("怎么实现关注列表", "game", 8);
        assertEquals("code", result.strategy());
        assertEquals(1, result.codeHitCount());
        assertEquals("game", result.bundle().resolvedProjectId());
    }

    @Test
    void reportsNoResultsWhenNothingHit() {
        CodeKnowledgeService code = mock(CodeKnowledgeService.class);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        RagProperties.ProjectConfig project = mock(RagProperties.ProjectConfig.class);
        when(registry.defaultProject()).thenReturn(project);
        when(project.id()).thenReturn("game");
        when(code.search("不存在的东西", "game", 8)).thenReturn(List.of());

        CodeOnlyStrategy strategy = new CodeOnlyStrategy(code, registry);
        StrategyResult result = strategy.execute(new RetrievalRequest("不存在的东西",
                com.example.requirementrag.retrieval.pipeline.RetrievalProfile.DEVELOPMENT_PLAN,
                "game", null, null, 8));

        assertEquals(com.example.requirementrag.model.RagOutcomeStatus.NO_RESULTS, result.status());
        assertTrue(result.bundle().codeEvidence().isEmpty());
    }
}
