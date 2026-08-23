package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.knowledge.multisource.KnowledgeGraphBuildService;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeGraphControllerTest {
    private MockMvc mvc;
    private MultiSourceKnowledgeStore store;
    private KnowledgeGraphBuildService buildService;
    private ProjectRegistry projectRegistry;
    private ProjectAccessGuard accessGuard;

    @BeforeEach
    void setUp() {
        store = mock(MultiSourceKnowledgeStore.class);
        buildService = mock(KnowledgeGraphBuildService.class);
        projectRegistry = mock(ProjectRegistry.class);
        accessGuard = mock(ProjectAccessGuard.class);
        KnowledgeGraphController controller = new KnowledgeGraphController(store, buildService, projectRegistry, accessGuard);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void queriesGraphWithProjectAccess() throws Exception {
        when(store.findEntities("immortal", "5.1")).thenReturn(List.of());
        when(store.findEntityRelations("immortal", "5.1")).thenReturn(List.of());

        mvc.perform(get("/api/knowledge/graph")
                        .param("projectId", "immortal")
                        .param("version", "5.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entities").isArray())
                .andExpect(jsonPath("$.relations").isArray());

        verify(projectRegistry).require("immortal");
        verify(accessGuard).requireProjectAccess(any(HttpServletRequest.class), eq("immortal"));
    }

    @Test
    void buildsGraph() throws Exception {
        when(buildService.build("immortal", "5.1"))
                .thenReturn(new KnowledgeGraphBuildService.GraphBuildResult(4, 2));

        mvc.perform(post("/api/knowledge/graph/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"immortal\",\"version\":\"5.1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entities").value(4))
                .andExpect(jsonPath("$.relations").value(2));

        verify(buildService).build("immortal", "5.1");
    }

    @Test
    void requiresWritePermissionOnBuild() throws Exception {
        assertThat(KnowledgeGraphController.class.getMethod("build",
                KnowledgeGraphController.BuildRequest.class, HttpServletRequest.class)
                .getAnnotation(RequiresPermission.class).value())
                .isEqualTo(Permission.WRITE);
    }
}