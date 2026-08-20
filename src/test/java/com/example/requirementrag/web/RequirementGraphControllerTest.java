package com.example.requirementrag.web;

import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.requirement.graph.RequirementGraphBuildService;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphProperties;
import com.example.requirementrag.requirement.graph.RequirementGraphSearchService;
import com.example.requirementrag.requirement.graph.SQLiteRequirementGraphStore;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequirementGraphControllerTest {
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
}
