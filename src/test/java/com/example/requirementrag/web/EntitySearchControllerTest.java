package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntitySearchResponse;
import com.example.requirementrag.knowledge.multisource.entity.EntityGraphExpansionService;
import com.example.requirementrag.knowledge.multisource.entity.EntityQueryService;
import com.example.requirementrag.knowledge.multisource.entity.EntityQueryService.EntitySearchRequest;
import com.example.requirementrag.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EntitySearchControllerTest {
    private MockMvc mvc;
    private EntityQueryService queryService;
    private ProjectRegistry projectRegistry;
    private ProjectAccessGuard accessGuard;

    @BeforeEach
    void setUp() {
        queryService = mock(EntityQueryService.class);
        projectRegistry = mock(ProjectRegistry.class);
        accessGuard = mock(ProjectAccessGuard.class);
        EntitySearchController controller = new EntitySearchController(
                queryService, mock(EntityGraphExpansionService.class), projectRegistry, accessGuard);
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
}
