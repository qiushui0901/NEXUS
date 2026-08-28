package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntityRecallResponse;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntitySearchResponse;
import com.example.requirementrag.knowledge.multisource.entity.EntityGraphExpansionService;
import com.example.requirementrag.knowledge.multisource.entity.RecallMode;
import com.example.requirementrag.knowledge.multisource.entity.EntityQueryService;
import com.example.requirementrag.knowledge.multisource.entity.EntityQueryService.EntitySearchRequest;
import com.example.requirementrag.knowledge.multisource.entity.EntityRecallService;
import com.example.requirementrag.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EntitySearchControllerTest {
    private MockMvc mvc;
    private EntityQueryService queryService;
    private EntityRecallService recallService;
    private ProjectRegistry projectRegistry;
    private ProjectAccessGuard accessGuard;

    @BeforeEach
    void setUp() {
        queryService = mock(EntityQueryService.class);
        recallService = mock(EntityRecallService.class);
        projectRegistry = mock(ProjectRegistry.class);
        accessGuard = mock(ProjectAccessGuard.class);
        EntitySearchController controller = new EntitySearchController(
                queryService, recallService, mock(EntityGraphExpansionService.class),
                projectRegistry, accessGuard);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void searchesEntityAndChecksProjectAccess() throws Exception {
        EntitySearchResponse response = new EntitySearchResponse(
                "角色达到 100 级时攻击力是多少？", null, List.of(), null, List.of(), List.of());
        when(queryService.search(any(EntitySearchRequest.class))).thenReturn(response);

        mvc.perform(post("/api/knowledge/entity-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId":"immortal",
                                  "query":"角色达到 100 级时攻击力是多少？",
                                  "limit": 20
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("角色达到 100 级时攻击力是多少？"));

        verify(projectRegistry).require("immortal");
    }

    @Test
    void graphVectorModeDispatchesToRecallService() throws Exception {
        when(recallService.search(any(EntitySearchRequest.class), eq(RecallMode.GRAPH_VECTOR)))
                .thenReturn(new EntityRecallResponse("Attack", null, List.of(), null, List.of(),
                        List.of(), "GRAPH_VECTOR", null, null, List.of(), 1));

        mvc.perform(post("/api/knowledge/entity-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId":"immortal",
                                  "query":"Attack 攻击力",
                                  "recallMode": "GRAPH_VECTOR"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recallMode").value("GRAPH_VECTOR"))
                .andExpect(jsonPath("$.relatedEntityCount").value(1));

        verify(recallService).search(any(EntitySearchRequest.class), eq(RecallMode.GRAPH_VECTOR));
    }

    @Test
    void defaultModeStaysDeterministic() throws Exception {
        when(queryService.search(any(EntitySearchRequest.class))).thenReturn(new EntitySearchResponse(
                "q", null, List.of(), null, List.of(), List.of()));

        mvc.perform(post("/api/knowledge/entity-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId":"immortal",
                                  "query":"Attack"
                                }
                                """))
                .andExpect(status().isOk());

        verify(queryService).search(any(EntitySearchRequest.class));
        verifyNoInteractions(recallService);
    }
}
