package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.knowledge.multisource.alignment.BusinessConceptService;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricAlignmentStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.AlignmentRelation;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BuildResult;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DoubtImpact;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DoubtImpactBuildResult;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DriftReport;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.VersionContext;
import com.example.requirementrag.knowledge.multisource.alignment.CodeParameterAlignmentService;
import com.example.requirementrag.knowledge.multisource.alignment.CodeTestAlignmentService;
import com.example.requirementrag.knowledge.multisource.alignment.DoubtImpactService;
import com.example.requirementrag.knowledge.multisource.alignment.RequirementCodeDriftService;
import com.example.requirementrag.knowledge.multisource.alignment.VersionContextService;
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

class CodeCentricAlignmentControllerTest {
    private MockMvc mvc;
    private ProjectRegistry projectRegistry;
    private ProjectAccessGuard accessGuard;
    private VersionContextService versionContextService;
    private BusinessConceptService businessConceptService;
    private CodeParameterAlignmentService codeParameterAlignmentService;
    private CodeTestAlignmentService codeTestAlignmentService;
    private RequirementCodeDriftService requirementCodeDriftService;
    private DoubtImpactService doubtImpactService;
    private CodeCentricAlignmentStore alignmentStore;

    @BeforeEach
    void setUp() {
        projectRegistry = mock(ProjectRegistry.class);
        accessGuard = mock(ProjectAccessGuard.class);
        versionContextService = mock(VersionContextService.class);
        businessConceptService = mock(BusinessConceptService.class);
        codeParameterAlignmentService = mock(CodeParameterAlignmentService.class);
        codeTestAlignmentService = mock(CodeTestAlignmentService.class);
        requirementCodeDriftService = mock(RequirementCodeDriftService.class);
        doubtImpactService = mock(DoubtImpactService.class);
        alignmentStore = mock(CodeCentricAlignmentStore.class);
        CodeCentricAlignmentController controller = new CodeCentricAlignmentController(
                projectRegistry, accessGuard, versionContextService, businessConceptService,
                codeParameterAlignmentService, codeTestAlignmentService, requirementCodeDriftService,
                doubtImpactService, alignmentStore);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void resolvesVersionContext() throws Exception {
        VersionContext context = new VersionContext("vc-1", "immortal", "5.1",
                "immortal-game-service", "abc123", "staging", "ACTIVE", null, null);
        when(versionContextService.resolve("immortal", "5.1", "staging")).thenReturn(context);

        mvc.perform(post("/api/knowledge/alignment/version-context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"immortal\",\"version\":\"5.1\",\"environment\":\"staging\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commitSha").value("abc123"));

        verify(versionContextService).resolve("immortal", "5.1", "staging");
        verify(accessGuard).requireProjectAccess(any(HttpServletRequest.class), eq("immortal"));
    }

    @Test
    void buildsConcepts() throws Exception {
        when(businessConceptService.build("immortal", "5.1"))
                .thenReturn(new BuildResult(4, 6, 8, 0, 0));

        mvc.perform(post("/api/knowledge/alignment/concepts/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"immortal\",\"version\":\"5.1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concepts").value(4))
                .andExpect(jsonPath("$.aliases").value(6))
                .andExpect(jsonPath("$.members").value(8));

        verify(businessConceptService).build("immortal", "5.1");
    }

    @Test
    void buildsProjectConceptsAcrossPublishedVersions() throws Exception {
        when(businessConceptService.buildProject("immortal"))
                .thenReturn(new BuildResult(9, 12, 20, 0, 0));

        mvc.perform(post("/api/knowledge/alignment/concepts/build-project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"immortal\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concepts").value(9))
                .andExpect(jsonPath("$.members").value(20));

        verify(businessConceptService).buildProject("immortal");
    }

    @Test
    void buildsCodeParameterAndCodeTest() throws Exception {
        when(codeParameterAlignmentService.build("immortal", "5.1", null))
                .thenReturn(new BuildResult(0, 0, 0, 3, 1));
        when(codeTestAlignmentService.build("immortal", "5.1", null))
                .thenReturn(new BuildResult(0, 0, 0, 2, 1));

        mvc.perform(post("/api/knowledge/alignment/code-parameter/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"immortal\",\"version\":\"5.1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relations").value(3));

        mvc.perform(post("/api/knowledge/alignment/code-test/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"immortal\",\"version\":\"5.1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relations").value(2));
    }

    @Test
    void reportsDrift() throws Exception {
        when(requirementCodeDriftService.report("immortal", "5.1", "staging"))
                .thenReturn(new DriftReport("immortal", "5.1", "abc123",
                        5, 2, 3, 1, 1, List.of()));

        mvc.perform(get("/api/knowledge/alignment/drift")
                        .param("projectId", "immortal")
                        .param("version", "5.1")
                        .param("environment", "staging"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aligned").value(5))
                .andExpect(jsonPath("$.documentDrift").value(2))
                .andExpect(jsonPath("$.unmapped").value(3))
                .andExpect(jsonPath("$.mappedNoAssertion").value(1));

        verify(requirementCodeDriftService).report("immortal", "5.1", "staging");
    }

    @Test
    void buildsDoubtImpact() throws Exception {
        when(doubtImpactService.build("immortal", "5.1", "staging"))
                .thenReturn(new DoubtImpactBuildResult(4, 1));

        mvc.perform(post("/api/knowledge/alignment/doubt-impact/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"immortal\",\"version\":\"5.1\",\"environment\":\"staging\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalImpacts").value(4))
                .andExpect(jsonPath("$.affectedDoubts").value(1));

        verify(doubtImpactService).build("immortal", "5.1", "staging");
    }

    @Test
    void queriesAndResolvesDoubtImpact() throws Exception {
        DoubtImpact impact = new DoubtImpact("imp-1", "immortal", "5.1", "vc-1", "d-1",
                "火球冷却是否已确认", "con-1", "doubt:combat", "CODE", null, "s-1",
                "resolveFireballCd", "P1", "tester", null, "OPEN", null, null, null, null);
        when(doubtImpactService.impacts("immortal", "5.1", "staging", "OPEN"))
                .thenReturn(List.of(impact));
        when(doubtImpactService.resolve("immortal", "5.1", "staging", "d-1",
                "已确认 12 秒", "ev-99"))
                .thenReturn(List.of(impact));

        mvc.perform(get("/api/knowledge/alignment/doubt-impact")
                        .param("projectId", "immortal")
                        .param("version", "5.1")
                        .param("environment", "staging")
                        .param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].doubtId").value("d-1"));

        mvc.perform(post("/api/knowledge/alignment/doubt-impact/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"immortal\",\"version\":\"5.1\",\"environment\":\"staging\","
                                + "\"doubtId\":\"d-1\",\"conclusion\":\"已确认 12 秒\",\"resolutionEvidenceId\":\"ev-99\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].doubtId").value("d-1"));

        verify(doubtImpactService).resolve("immortal", "5.1", "staging", "d-1", "已确认 12 秒", "ev-99");
    }

    @Test
    void reviewsAlignmentRelation() throws Exception {
        AlignmentRelation relation = new AlignmentRelation(
                "ar-1", "immortal", "5.1", "vc-1", "p-1", null, "PARAMETER_TABLE",
                null, "s-1", "CODE", "READS_CONFIG", "NORMALIZED_NAME_EXACT", "HUMAN_CONFIRMED",
                0.9, null, "vc-1", "vc-1", "confirmed", null, null);
        when(alignmentStore.findAlignmentRelationById("ar-1")).thenReturn(java.util.Optional.of(relation));

        mvc.perform(post("/api/knowledge/alignment/alignment-relation/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"immortal\",\"relationId\":\"ar-1\",\"action\":\"HUMAN_CONFIRMED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HUMAN_CONFIRMED"));

        verify(alignmentStore).reviewAlignmentRelation("ar-1", "HUMAN_CONFIRMED");
    }

    @Test
    void buildEndpointsRequireWritePermission() throws Exception {
        assertThat(CodeCentricAlignmentController.class.getMethod("buildConcepts",
                CodeCentricAlignmentController.ScopeRequest.class, HttpServletRequest.class)
                .getAnnotation(RequiresPermission.class).value())
                .isEqualTo(Permission.WRITE);
        assertThat(CodeCentricAlignmentController.class.getMethod("buildDrift",
                CodeCentricAlignmentController.ScopeRequest.class, HttpServletRequest.class)
                .getAnnotation(RequiresPermission.class).value())
                .isEqualTo(Permission.WRITE);
        assertThat(CodeCentricAlignmentController.class.getMethod("buildDoubtImpact",
                CodeCentricAlignmentController.ScopeRequest.class, HttpServletRequest.class)
                .getAnnotation(RequiresPermission.class).value())
                .isEqualTo(Permission.WRITE);
    }
}