package com.example.requirementrag.web;

import com.example.requirementrag.knowledge.build.KnowledgeBuildModels.BuildResult;
import com.example.requirementrag.knowledge.build.KnowledgeBuildModels.BuildStatus;
import com.example.requirementrag.knowledge.build.VersionKnowledgeBuildPipeline;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeBuildControllerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        VersionKnowledgeBuildPipeline pipeline = mock(VersionKnowledgeBuildPipeline.class);
        when(pipeline.build(any())).thenReturn(new BuildResult("build-1", BuildStatus.DRAFT,
                2, 0, 1, 2, "data/wiki-drafts/game/5.1/build-1", "2026-07-24T00:00:00Z", List.of()));
        KnowledgeBuildController controller = new KnowledgeBuildController(pipeline, mock(ProjectAccessGuard.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void createsDraftThroughWriteProtectedApi() throws Exception {
        mvc.perform(post("/api/knowledge/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":"game","version":"5.1","baseVersion":"5.0",
                                 "documentId":"requirements","baseCodeCommit":"base","codeCommit":"head"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.features").value(2))
                .andExpect(jsonPath("$.draftPath").value("data/wiki-drafts/game/5.1/build-1"));
    }

    @Test
    void rejectsMissingRequiredFields() throws Exception {
        mvc.perform(post("/api/knowledge/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"5.1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresWritePermission() throws Exception {
        assertThat(KnowledgeBuildController.class.getMethod("build",
                com.example.requirementrag.knowledge.build.KnowledgeBuildModels.BuildRequest.class,
                HttpServletRequest.class).getAnnotation(RequiresPermission.class).value())
                .isEqualTo(Permission.WRITE);
    }
}
