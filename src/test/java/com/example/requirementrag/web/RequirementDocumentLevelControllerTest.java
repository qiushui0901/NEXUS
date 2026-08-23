package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.requirement.graph.document.DocumentLevelBuildService;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.BuildFingerprint;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.BuildMetrics;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.DocumentLevelBuildResult;
import com.example.requirementrag.requirement.graph.document.RequirementDocumentStructureStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RequirementDocumentLevelControllerTest {
    private MockMvc mvc;
    private ProjectRegistry projectRegistry;
    private ProjectAccessGuard accessGuard;
    private DocumentLevelBuildService buildService;
    private RequirementDocumentStructureStore store;

    @BeforeEach
    void setUp() {
        projectRegistry = mock(ProjectRegistry.class);
        accessGuard = mock(ProjectAccessGuard.class);
        buildService = mock(DocumentLevelBuildService.class);
        store = mock(RequirementDocumentStructureStore.class);
        RequirementDocumentLevelController controller = new RequirementDocumentLevelController(
                projectRegistry, accessGuard, buildService, store);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void buildsDocumentLevelExtraction() throws Exception {
        BuildFingerprint fingerprint = new BuildFingerprint("rev-1", "v1", "v1", "v1", "v2", "v1", "RULE", "v1");
        DocumentLevelBuildResult result = new DocumentLevelBuildResult("doc-1", "5.1", fingerprint,
                new BuildMetrics(1, 2, 50, 500, 2, 4, 4, 1, 0, 0),
                List.of(), List.of(), List.of(), List.of(), List.of());
        when(buildService.build(eq("doc-1"), eq("5.1"), eq("rev-1"), any(String.class))).thenReturn(result);

        mvc.perform(post("/api/requirement-graphs/document-level/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"immortal\",\"documentId\":\"doc-1\","
                                + "\"requirementVersion\":\"5.1\",\"documentRevision\":\"rev-1\",\"text\":\"REQ-001 x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value("doc-1"))
                .andExpect(jsonPath("$.metrics.windowCount").value(2));

        verify(buildService).build("doc-1", "5.1", "rev-1", "REQ-001 x");
    }
}
