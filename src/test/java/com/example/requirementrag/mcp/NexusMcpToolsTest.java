package com.example.requirementrag.mcp;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.model.UserRole;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalPipeline;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.security.ProjectAuthorizationService;
import com.example.requirementrag.service.DevelopmentPlanService;
import com.example.requirementrag.versioning.VersionComparisonService;
import com.example.requirementrag.web.AccessDeniedException;
import com.example.requirementrag.wiki.WikiRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NexusMcpToolsTest {

    private RetrievalPipeline retrievalPipeline;
    private DevelopmentPlanService developmentPlanService;
    private NexusMcpTools tools;

    @BeforeEach
    void setUp() {
        retrievalPipeline = mock(RetrievalPipeline.class);
        developmentPlanService = mock(DevelopmentPlanService.class);
        ProjectAuthorizationService authorization = new ProjectAuthorizationService(mock(ProjectRegistry.class));
        McpResponsePolicy policy = new McpResponsePolicy(
                new McpProperties(true, 20, 200, 2_000, 40, 120_000),
                JsonMapper.builder().build());
        McpToolInvocationService invocations = new McpToolInvocationService(
                authorization, new SimpleMeterRegistry(), policy);
        tools = new NexusMcpTools(retrievalPipeline, mock(CodeKnowledgeService.class),
                developmentPlanService, mock(WikiRepository.class), mock(VersionComparisonService.class),
                policy, invocations);
    }

    @Test
    void requirementSearchReturnsResolvedScopeAndStableEvidence() {
        ChunkRecord chunk = new ChunkRecord("internal-point", "requirements", "5.1", "req.md",
                "parent-1", "Requirement text", "child", "hash", 1, 0);
        RetrievalBundle bundle = new RetrievalBundle("query", RetrievalProfile.REQUIREMENT_REVIEW,
                "project-a", "requirements", "5.1", List.of(chunk), List.of());
        when(retrievalPipeline.execute(any())).thenReturn(new RagOutcome<>(
                RagOutcomeStatus.SUCCESS, bundle, List.of(), List.of()));

        McpToolResponse<List<McpResponsePolicy.RequirementHit>> response = tools.searchRequirements(
                context(UserRole.READONLY), "query", "project-a", "requirements", "5.1", 10);

        assertEquals("project-a", response.resolved().projectId());
        assertEquals("5.1", response.resolved().version());
        assertEquals(1, response.data().size());
        assertTrue(response.data().getFirst().evidenceId().startsWith("requirement:"));
        assertEquals(1, response.evidence().size());
        assertTrue(response.evidence().getFirst().toString().contains("requirement:"));
        assertTrue(response.evidence().getFirst().toString().contains("chunkId=null"));
    }

    @Test
    void developmentPlanKeepsExistingOperatePermission() {
        McpSyncRequestContext viewer = context(UserRole.READONLY);

        assertThrows(AccessDeniedException.class, () -> tools.developmentPlan(
                viewer, "query", "project-a", null, null, 10));
    }

    private McpSyncRequestContext context(UserRole role) {
        McpSyncRequestContext context = mock(McpSyncRequestContext.class);
        UserContext user = new UserContext("actor", role, List.of("project-a"));
        when(context.transportContext()).thenReturn(McpTransportContext.create(
                Map.of(McpTransportConfiguration.USER_CONTEXT_KEY, user)));
        return context;
    }
}
