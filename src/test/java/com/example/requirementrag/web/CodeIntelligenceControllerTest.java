package com.example.requirementrag.web;

import com.example.requirementrag.code.CodeIndexJobService;
import com.example.requirementrag.code.CodeIntelligenceService;
import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.code.IncrementalCodeIndexService;
import com.example.requirementrag.model.CodeIntelligenceResponse;
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
