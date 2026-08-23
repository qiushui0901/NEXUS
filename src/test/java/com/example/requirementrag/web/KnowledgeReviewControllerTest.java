package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeReviewControllerTest {
    private MockMvc mvc;
    private MultiSourceKnowledgeStore store;
    private ProjectRegistry projectRegistry;
    private ProjectAccessGuard accessGuard;

    @BeforeEach
    void setUp() {
        store = mock(MultiSourceKnowledgeStore.class);
        projectRegistry = mock(ProjectRegistry.class);
        accessGuard = mock(ProjectAccessGuard.class);
        KnowledgeReviewController controller = new KnowledgeReviewController(store, projectRegistry, accessGuard);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void reviewsRelationAndChecksProjectAccess() throws Exception {
        mvc.perform(post("/api/knowledge/review/relations/rel-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"fengshen\",\"status\":\"HUMAN_CONFIRMED\",\"reason\":\"已核对\"}"))
                .andExpect(status().isOk());

        verify(store).reviewRelation("rel-1", "HUMAN_CONFIRMED", "HUMAN", "已核对");
        verify(projectRegistry).require("fengshen");
        verify(accessGuard).requireProjectAccess(any(HttpServletRequest.class), eq("fengshen"));
    }

    @Test
    void rejectsMissingStatus() throws Exception {
        mvc.perform(post("/api/knowledge/review/relations/rel-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"fengshen\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresWritePermission() throws Exception {
        assertThat(KnowledgeReviewController.class.getMethod("reviewRelation",
                String.class, KnowledgeReviewController.ReviewRequest.class, HttpServletRequest.class)
                .getAnnotation(RequiresPermission.class).value())
                .isEqualTo(Permission.WRITE);
    }
}