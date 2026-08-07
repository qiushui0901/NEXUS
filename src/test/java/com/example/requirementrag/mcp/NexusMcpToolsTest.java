package com.example.requirementrag.mcp;

import com.example.requirementrag.code.CodeIntelligenceService;
import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeIntelligenceResponse;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.model.UserRole;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.agentic.AgenticOrchestrator;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.security.ProjectAuthorizationService;
import com.example.requirementrag.service.DevelopmentPlanService;
import com.example.requirementrag.service.ReviewFacadeService;
import com.example.requirementrag.versioning.VersionComparisonService;
import com.example.requirementrag.web.AccessDeniedException;
import com.example.requirementrag.wiki.WikiRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NexusMcpToolsTest {

    private AgenticOrchestrator orchestrator;
    private DevelopmentPlanService developmentPlanService;
    private CodeIntelligenceService codeIntelligenceService;
    private NexusMcpTools tools;

    @BeforeEach
    void setUp() {
        orchestrator = mock(AgenticOrchestrator.class);
        developmentPlanService = mock(DevelopmentPlanService.class);
        codeIntelligenceService = mock(CodeIntelligenceService.class);
        ProjectAuthorizationService authorization = new ProjectAuthorizationService(mock(ProjectRegistry.class));
        McpResponsePolicy policy = new McpResponsePolicy(
                new McpProperties(true, 20, 200, 2_000, 40, 120_000),
                JsonMapper.builder().build());
        McpToolInvocationService invocations = new McpToolInvocationService(
                authorization, new SimpleMeterRegistry(), policy);
        tools = new NexusMcpTools(orchestrator, mock(CodeKnowledgeService.class),
                developmentPlanService, mock(WikiRepository.class), mock(VersionComparisonService.class),
                policy, invocations, codeIntelligenceService, mock(ReviewFacadeService.class));
    }

    @Test
    void requirementSearchReturnsResolvedScopeAndStableEvidence() {
        ChunkRecord chunk = new ChunkRecord("internal-point", "requirements", "5.1", "req.md",
                "parent-1", "Requirement text", "child", "hash", 1, 0);
        RetrievalBundle bundle = new RetrievalBundle("query", RetrievalProfile.REQUIREMENT_REVIEW,
                "project-a", "requirements", "5.1", List.of(chunk), List.of());
        when(orchestrator.execute(any())).thenReturn(new RagOutcome<>(
                RagOutcomeStatus.SUCCESS, bundle, List.of(), List.of()));

        context(UserRole.READONLY);
        McpToolResponse<List<McpResponsePolicy.RequirementHit>> response =
                tools.nexus_search_requirements("query", "project-a", "requirements", "5.1", 10);

        assertEquals("project-a", response.resolved().projectId());
        assertEquals("5.1", response.resolved().version());
        assertEquals(1, response.data().size());
        assertTrue(response.data().get(0).evidenceId().startsWith("requirement:"));
        assertEquals(1, response.evidence().size());
        assertTrue(response.evidence().get(0).toString().contains("requirement:"));
        assertTrue(response.evidence().get(0).toString().contains("chunkId=null"));
    }

    @Test
    void developmentPlanKeepsExistingOperatePermission() {
        context(UserRole.READONLY);

        assertThrows(AccessDeniedException.class, () -> { context(UserRole.READONLY); tools.nexus_development_plan("query", "project-a", null, null, 10); });
    }

    @Test
    void codeGraphPreservesNotAvailableWarningInTheMcpEnvelope() {
        when(codeIntelligenceService.graph("project-a", "missing", null, 2, 50))
                .thenReturn(new CodeIntelligenceResponse("NOT_AVAILABLE", "project-a", null,
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of("No code graph snapshot; run code index first"), false));

        context(UserRole.READONLY);
        McpToolResponse<CodeIntelligenceResponse> response =
                tools.nexus_code_graph("missing", "project-a", null, 2, 50);

        assertTrue(response.quality().toString().contains("NOT_AVAILABLE"));
        assertEquals(1, response.warnings().size());
        assertEquals("CODE_GRAPH_DEGRADED", response.warnings().get(0).code());
    }

    @Test
    void impactAnalysisRequiresExactlyOneSelectorMode() {
        context(UserRole.READONLY);

        assertThrows(IllegalArgumentException.class, () -> tools.nexus_impact_analysis("project-a", null, null, null, 2, 50));
        assertThrows(IllegalArgumentException.class, () -> tools.nexus_impact_analysis("project-a", "save", "aaaaaaa", "bbbbbbb", 2, 50));
    }

    private void context(UserRole role) {
        UserContext user = new UserContext("actor", role, List.of("project-a"));
        McpUserContextHolder.set(user);
    }
}
