package com.example.requirementrag.web;

import com.example.requirementrag.code.CodeIndexJobService;
import com.example.requirementrag.code.CodeIntelligenceService;
import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.code.IncrementalCodeIndexService;
import com.example.requirementrag.model.CodeIntelligenceResponse;
import com.example.requirementrag.model.CodeGraphRequest;
import com.example.requirementrag.model.ImpactAnalysisRequest;
import com.example.requirementrag.model.SymbolGraphRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeIntelligenceControllerTest {

    @Test
    void multiRepositoryBusinessProjectRejectsAmbiguousGraphImpactAndSourceRequests() throws Exception {
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        CodeIntelligenceService intelligence = mock(CodeIntelligenceService.class);
        com.example.requirementrag.project.BusinessProjectCatalogService catalog =
                mock(com.example.requirementrag.project.BusinessProjectCatalogService.class);
        com.example.requirementrag.project.CodeRepository first = repository("repo-a");
        com.example.requirementrag.project.CodeRepository second = repository("repo-b");
        when(catalog.repositoryScope("immortal", List.of())).thenReturn(List.of(first, second));
        CodeController controller = new CodeController(mock(CodeKnowledgeService.class),
                mock(IncrementalCodeIndexService.class), mock(CodeIndexJobService.class), access, intelligence,
                mock(com.example.requirementrag.project.BusinessProjectCodeSearchService.class), catalog);
        HttpServletRequest request = mock(HttpServletRequest.class);

        assertThatThrownBy(() -> controller.graph(
                new CodeGraphRequest("订单取消", "immortal", null, "flow", 10, false), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repositoryId");
        assertThatThrownBy(() -> controller.symbolGraph(
                new SymbolGraphRequest("immortal", "OrderService.cancel", "inbound", 2, 20), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repositoryId");
        assertThatThrownBy(() -> controller.impact(
                new ImpactAnalysisRequest("immortal", "OrderService.cancel", null, null, 2, 20), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repositoryId");
        assertThatThrownBy(() -> controller.source("immortal", null, "src/Order.java", 1, 10, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repositoryId");
    }

    private com.example.requirementrag.project.CodeRepository repository(String id) {
        return new com.example.requirementrag.project.CodeRepository(id, id,
                com.example.requirementrag.project.CodeRepository.Kind.PROJECT, "immortal", "server",
                "code_" + id, "/tmp/" + id, "", "", "", true, true, "", "");
    }

    @Test
    void graphAndImpactEnforceProjectScopeAndSelectorValidation() {
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        CodeIntelligenceService intelligence = mock(CodeIntelligenceService.class);
        CodeController controller = new CodeController(mock(CodeKnowledgeService.class),
                mock(IncrementalCodeIndexService.class), mock(CodeIndexJobService.class), access, intelligence);
        HttpServletRequest request = mock(HttpServletRequest.class);
        CodeIntelligenceResponse unavailable = new CodeIntelligenceResponse(
                "NOT_AVAILABLE", "project-a", null, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of("index first"), false);
        when(intelligence.graph("project-a", "save", "inbound", 2, 50)).thenReturn(unavailable);

        CodeIntelligenceResponse response = controller.symbolGraph(
                new SymbolGraphRequest("project-a", "save", "inbound", 2, 50), request);

        assertThat(response.availability()).isEqualTo("NOT_AVAILABLE");
        verify(access).requireProjectAccess(request, "project-a");
        assertThatThrownBy(() -> controller.impact(
                new ImpactAnalysisRequest("project-a", null, null, null, 2, 50), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one impact mode");
    }
}
