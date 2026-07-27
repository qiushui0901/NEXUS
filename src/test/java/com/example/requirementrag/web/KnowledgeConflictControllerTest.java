package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.conflict.KnowledgeConflictModels.AnalyzeRequest;
import com.example.requirementrag.conflict.KnowledgeConflictService;
import com.example.requirementrag.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeConflictControllerTest {
    private MockMvc mvc;
    private ProjectRegistry projectRegistry;
    private ProjectAccessGuard accessGuard;

    @BeforeEach
    void setUp() {
        projectRegistry = mock(ProjectRegistry.class);
        accessGuard = mock(ProjectAccessGuard.class);
        KnowledgeConflictController controller = new KnowledgeConflictController(
                new KnowledgeConflictService(), projectRegistry, accessGuard);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void analyzesStructuredClaimsAndChecksProjectAccess() throws Exception {
        mvc.perform(post("/api/knowledge/conflicts/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId":"sample",
                                  "targetVersion":"2.1",
                                  "claims":[
                                    {
                                      "projectId":"sample","version":"2.1","factKey":"state","value":"open",
                                      "sourceType":"REQUIREMENT","authority":"PRIMARY",
                                      "evidence":{"evidenceId":"req-1","source":"sample.txt"},
                                      "supportingEvidenceIds":[]
                                    },
                                    {
                                      "projectId":"sample","version":"2.1","factKey":"state","value":"closed",
                                      "sourceType":"CODE","authority":"PRIMARY",
                                      "evidence":{"evidenceId":"code-1","source":"SampleService.java"},
                                      "supportingEvidenceIds":[]
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.conflictCount").value(1))
                .andExpect(jsonPath("$.conflicts[0].type").value("REQUIREMENT_CODE"));

        verify(projectRegistry).require("sample");
        verify(accessGuard).requireProjectAccess(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("sample"));
    }

    @Test
    void rejectsMissingScope() throws Exception {
        mvc.perform(post("/api/knowledge/conflicts/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"claims\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresOperatePermission() throws Exception {
        assertThat(KnowledgeConflictController.class.getMethod("analyze", AnalyzeRequest.class,
                HttpServletRequest.class).getAnnotation(RequiresPermission.class).value())
                .isEqualTo(Permission.OPERATE);
    }
}
