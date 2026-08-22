package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.MultiSourceSearchRequest;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.MultiSourceSearchResponse;
import com.example.requirementrag.knowledge.multisource.MultiSourceSearchService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MultiSourceKnowledgeControllerTest {
    private MockMvc mvc;
    private MultiSourceSearchService searchService;
    private ProjectRegistry projectRegistry;
    private ProjectAccessGuard accessGuard;

    @BeforeEach
    void setUp() {
        searchService = mock(MultiSourceSearchService.class);
        projectRegistry = mock(ProjectRegistry.class);
        accessGuard = mock(ProjectAccessGuard.class);
        MultiSourceKnowledgeController controller = new MultiSourceKnowledgeController(
                searchService, projectRegistry, accessGuard);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void searchesMultiSourceKnowledgeAndChecksProjectAccess() throws Exception {
        MultiSourceSearchResponse response = new MultiSourceSearchResponse(
                "权限撤销传播时间是多少", KnowledgeQueryIntent.PARAMETER,
                MultiSourceKnowledgeModels.AnswerStatus.CONFIRMED,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(searchService.search(eq("fengshen"), eq("5.1"), eq("权限撤销传播时间是多少"),
                any(), anyInt(), anyInt())).thenReturn(response);

        mvc.perform(post("/api/knowledge/multi-source/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId":"fengshen",
                                  "version":"5.1",
                                  "query":"权限撤销传播时间是多少"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("PARAMETER"))
                .andExpect(jsonPath("$.answerStatus").value("CONFIRMED"));

        verify(searchService).search(eq("fengshen"), eq("5.1"), eq("权限撤销传播时间是多少"),
                eq(null), eq(20), eq(0));
        verify(projectRegistry).require("fengshen");
        verify(accessGuard).requireProjectAccess(any(HttpServletRequest.class), eq("fengshen"));
    }

    @Test
    void rejectsMissingProjectScope() throws Exception {
        mvc.perform(post("/api/knowledge/multi-source/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"5.1\",\"query\":\"测试\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresPublicReadPermission() throws Exception {
        assertThat(MultiSourceKnowledgeController.class.getMethod("search",
                MultiSourceSearchRequest.class, HttpServletRequest.class)
                .getAnnotation(RequiresPermission.class).value())
                .isEqualTo(Permission.PUBLIC_READ);
    }
}