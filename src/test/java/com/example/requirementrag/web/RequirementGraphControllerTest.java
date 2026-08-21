package com.example.requirementrag.web;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.requirement.graph.RequirementGraphBuildJobService;
import com.example.requirementrag.requirement.graph.RequirementGraphBuildService;
import com.example.requirementrag.requirement.graph.RequirementGraphHybridSearchService;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ClaimStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphPath;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.QueryPlan;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchMode;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SearchResponse;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphProperties;
import com.example.requirementrag.requirement.graph.RequirementGraphQueryPlanner;
import com.example.requirementrag.requirement.graph.RequirementGraphSearchService;
import com.example.requirementrag.requirement.graph.SQLiteRequirementGraphStore;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RequirementGraphControllerTest {
    private MockMvc mvc;
    private RequirementGraphHybridSearchService hybrid;
    private RequirementGraphQueryPlanner planner;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RequirementGraphBuildService buildService = mock(RequirementGraphBuildService.class);
        RequirementGraphSearchService searchService = mock(RequirementGraphSearchService.class);
        SQLiteRequirementGraphStore store = mock(SQLiteRequirementGraphStore.class);
        ProjectAccessGuard accessGuard = mock(ProjectAccessGuard.class);
        RequirementGraphProperties properties = mock(RequirementGraphProperties.class);
        hybrid = mock(RequirementGraphHybridSearchService.class);
        RequirementGraphBuildJobService buildJobService = mock(RequirementGraphBuildJobService.class);
        planner = mock(RequirementGraphQueryPlanner.class);

        ObjectProvider<RequirementGraphHybridSearchService> hybridProvider = mock(ObjectProvider.class);
        when(hybridProvider.getIfAvailable()).thenReturn(hybrid);
        ObjectProvider<RequirementGraphBuildJobService> jobProvider = mock(ObjectProvider.class);
        when(jobProvider.getIfAvailable()).thenReturn(buildJobService);
        ObjectProvider<RequirementGraphQueryPlanner> plannerProvider = mock(ObjectProvider.class);
        when(plannerProvider.getIfAvailable()).thenReturn(planner);

        RequirementGraphController controller = new RequirementGraphController(buildService, searchService, store,
                accessGuard, properties, hybridProvider, jobProvider, plannerProvider);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void publishingRequiresProjectAccessAndMovesReviewDraftToPublished() {
        RequirementGraphBuildService buildService = mock(RequirementGraphBuildService.class);
        RequirementGraphSearchService searchService = mock(RequirementGraphSearchService.class);
        SQLiteRequirementGraphStore store = mock(SQLiteRequirementGraphStore.class);
        ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
        RequirementGraphProperties properties = new RequirementGraphProperties(
                true, true, true, "", 20, 30, 20_000, 2, 40, "model", "v1");
        HttpServletRequest request = mock(HttpServletRequest.class);
        GraphSnapshot draft = new GraphSnapshot("reqgraph:one", "orders", "requirements", "2.0",
                "source", "model", "v1", SnapshotStatus.REVIEW_REQUIRED, 2, 1,
                Instant.now(), Instant.now(), null);
        when(store.requireSnapshot(draft.id())).thenReturn(draft);

        RequirementGraphController controller = new RequirementGraphController(
                buildService, searchService, store, guard, properties);
        controller.publish(draft.id(), request);

        verify(guard).requireProjectAccess(request, "orders");
        verify(store).updateStatus(draft.id(), SnapshotStatus.PUBLISHED, null);
    }

    @Test
    void mixSearchRoutesThroughHybridServiceAndExposesFusedChannels() throws Exception {
        QueryPlan plan = new QueryPlan(SearchMode.MIX, List.of("取消订单"), List.of("影响"), List.of("取消订单"),
                2, 20, 20, 20, Set.of(ClaimStatus.VERIFIED));
        when(planner.plan(any())).thenReturn(plan);
        ChunkRecord chunk = new ChunkRecord("chunk:http", "requirements", "2.0", "orders.md", "p1",
                "取消订单相关需求", "用户发起取消订单", "hash", 0, 0);
        SearchResponse response = new SearchResponse(null, List.of(), List.of(), List.of(), List.of(),
                1, false, 0, 10, List.of(chunk), new ArrayList<>(List.of(
                        new GraphPath(List.of("entity:a", "entity:b"), List.of("rel:ab"), 1, 1.0))),
                plan, Map.of("text", 0.30, "entity", 0.20, "relation", 0.15, "path", 0.15, "evidence", 0.15, "freshness", 0.05));
        when(hybrid.search(any(), eq(plan))).thenReturn(response);

        mvc.perform(post("/api/requirement-graphs/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"orders","documentId":"requirements","requirementVersion":"2.0",
                                 "query":"取消订单","mode":"MIX"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceChunks[0].id").value("chunk:http"))
                .andExpect(jsonPath("$.paths[0].hops").value(1))
                .andExpect(jsonPath("$.channelScores.text").value(0.30))
                .andExpect(jsonPath("$.plan.mode").value("MIX"));

        verify(hybrid).search(any(), eq(plan));
    }
}