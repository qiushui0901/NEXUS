package com.example.requirementrag.web;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeProperties;
import com.example.requirementrag.project.BusinessProjectCatalogService;
import com.example.requirementrag.requirement.semantic.RequirementSemanticBuildService;
import com.example.requirementrag.requirement.semantic.RequirementSemanticException;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildAggregateView;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildRequest;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildResult;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildStatus;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildStatusView;
import com.example.requirementrag.requirement.semantic.RequirementSemanticProperties;
import com.example.requirementrag.requirement.semantic.SQLiteRequirementSemanticStore;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 语义构建 Controller：委托构建服务并强制项目注册与访问控制。 */
class RequirementSemanticBuildControllerTest {
    private final RequirementSemanticBuildService buildService = mock(RequirementSemanticBuildService.class);
    private final SQLiteRequirementSemanticStore store = mock(SQLiteRequirementSemanticStore.class);
    private final BusinessProjectCatalogService businessProjects = mock(BusinessProjectCatalogService.class);
    private final ProjectAccessGuard accessGuard = mock(ProjectAccessGuard.class);
    private final RequirementSemanticProperties properties =
            new RequirementSemanticProperties(true, true, true, false,
                    "data/requirement-semantic.db", null,
                    "requirement-semantic-v1", "v1", 12_000, 30, 30, 30, 30, 20, 30, 2,
                    1_000, 1_800, 1_000_000, 400, true, 5_000);
    private final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    private final MultiSourceKnowledgeProperties multiSourceProperties =
            new MultiSourceKnowledgeProperties(true, false, null, Map.of("p1", true), false);

    private final RequirementSemanticBuildController controller = new RequirementSemanticBuildController(
            buildService, store, accessGuard, businessProjects, properties, multiSourceProperties);

    /** 默认：请求的业务 ID 即规范 ID（mock resolveProjectId 返回 null 会让 requireProject(null) 空转）。 */
    private void stubIdentityResolution() {
        when(businessProjects.resolveProjectId(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void verifyRequireProject(String projectId) {
        verify(businessProjects).requireProject(projectId);
    }

    @Test
    void buildDelegatesToServiceAndEnforcesProjectAccess() {
        stubIdentityResolution();
        SemanticBuildResult result = new SemanticBuildResult("p1", "doc", "5.1", "rev-1",
                "test-model", "requirement-semantic-v1", "v1",
                1, 0, 1, 0, SemanticBuildStatus.SUCCESS, List.of(), List.of());
        when(buildService.build(any())).thenReturn(result);

        SemanticBuildResult actual = controller.build(
                new SemanticBuildRequest("p1", "doc", "5.1", null), httpRequest);

        assertThat(actual).isEqualTo(result);
        verify(businessProjects).requireProject("p1");
        verify(accessGuard).requireProjectAccess(httpRequest, "p1");
        verify(buildService).build(any());
    }

    @Test
    void buildRejectsNullOrBlankProjectIdBeforeDirectoryResolution() {
        // 中（Review）：空 projectId 不得被目录解析静默改写为默认项目——BusinessProjectCatalogService
        // 对 null/空会选第一个项目，若放行则缺失 projectId 的 POST 绕过 SEMANTIC_REQUEST_INVALID，
        // 启动一个耗时且调用模型的默认项目构建。必须在目录解析前拒绝 null/空串/纯空白。
        assertThatThrownBy(() -> controller.build(
                new SemanticBuildRequest(null, "doc", "5.1", null), httpRequest))
                .isInstanceOf(RequirementSemanticException.class)
                .satisfies(throwable -> assertThat(((RequirementSemanticException) throwable).code()).isEqualTo("SEMANTIC_REQUEST_INVALID"))
                .hasMessageContaining("项目 ID 不能为空");
        assertThatThrownBy(() -> controller.build(
                new SemanticBuildRequest("   ", "doc", "5.1", null), httpRequest))
                .isInstanceOf(RequirementSemanticException.class)
                .satisfies(throwable -> assertThat(((RequirementSemanticException) throwable).code()).isEqualTo("SEMANTIC_REQUEST_INVALID"));
        // 空 projectId 不应触发目录解析或鉴权（不会启动任何构建）。
        verify(businessProjects, never()).resolveProjectId(any());
        verify(businessProjects, never()).requireProject(any());
        verify(accessGuard, never()).requireProjectAccess(any(), any());
        verify(buildService, never()).build(any());
    }

    @Test
    void buildNormalizesLegacyProjectIdToBusinessIdBeforeDelegating() {
        // 页面返回业务项目 ID（如 immortal），legacy 静态配置的 ID 是别名（如 immortal-game-service）。
        // Controller 入口必须把 legacy 别名解析为规范业务 ID 再鉴权/落库，否则状态查询永远失配。
        when(businessProjects.resolveProjectId("immortal-game-service")).thenReturn("immortal");        SemanticBuildResult result = new SemanticBuildResult("immortal", "doc", "5.1", "rev-1",
                "test-model", "requirement-semantic-v1", "v1",
                1, 0, 1, 0, SemanticBuildStatus.SUCCESS, List.of(), List.of());
        when(buildService.build(any())).thenReturn(result);

        SemanticBuildResult actual = controller.build(
                new SemanticBuildRequest("immortal-game-service", "doc", "5.1", null), httpRequest);

        assertThat(actual).isEqualTo(result);
        verify(businessProjects).requireProject("immortal");
        verify(accessGuard).requireProjectAccess(httpRequest, "immortal");
        // 委托给服务的请求携带规范 ID：buildService 落库用 immortal，后续按 immortal 查询必然命中。
        verify(buildService).build(org.mockito.ArgumentMatchers.argThat(request ->
                request.projectId().equals("immortal") && request.documentId().equals("doc")));
    }

    @Test
    void latestBuildReadsMostRecentStatusView() {
        stubIdentityResolution();
        SemanticBuildStatusView view = new SemanticBuildStatusView("run-1", "build-1", "p1", "doc", "5.1",
                "rev-1", "test-model", "requirement-semantic-v1", "v1",
                SemanticBuildStatus.PARTIAL_FAILURE, 2, 1, 0, 1, List.of("SEMANTIC_BUDGET_MODEL_CALLS"),
                Instant.now(), Instant.now(), true, "build-1", "rev-1", SemanticBuildStatus.SUCCESS);
        when(store.latestBuild("p1", "doc", "5.1")).thenReturn(Optional.of(view));

        Optional<SemanticBuildStatusView> actual = controller.latestBuild("p1", "doc", "5.1", httpRequest);

        assertThat(actual).contains(view);
        // 视图字段拆分执行状态与代际状态：最新执行失败但成功代际仍在线的语义可直接表达。
        assertThat(actual).isPresent()
                .get()
                .satisfies(latest -> {
                    assertThat(latest.runId()).isEqualTo("run-1");
                    assertThat(latest.latestRunStatus()).isEqualTo(SemanticBuildStatus.PARTIAL_FAILURE);
                    assertThat(latest.generationActive()).isTrue();
                    assertThat(latest.activeGenerationBuildId()).isEqualTo("build-1");
                    assertThat(latest.activeGenerationStatus()).isEqualTo(SemanticBuildStatus.SUCCESS);
                    // 兼容旧 SemanticBuildRecord JSON 字段的访问器仍可用。
                    assertThat(latest.buildStatus()).isEqualTo(SemanticBuildStatus.PARTIAL_FAILURE);
                    assertThat(latest.active()).isTrue();
                });
        verify(businessProjects).requireProject("p1");
        verify(accessGuard).requireProjectAccess(httpRequest, "p1");
    }

    @Test
    void latestBuildJsonKeepsLegacyBuildStatusAndActiveFields() throws Exception {
        stubIdentityResolution();
        // @JsonProperty 兼容字段必须出现在 HTTP JSON 中：普通无 get 前缀方法 Jackson 默认不序列化。
        Instant started = Instant.parse("2026-08-26T07:56:40.227588Z");
        Instant finished = Instant.parse("2026-08-26T07:56:40.227590Z");
        SemanticBuildStatusView view = new SemanticBuildStatusView("run-1", "build-1", "p1", "doc", "5.1",
                "rev-1", "test-model", "requirement-semantic-v1", "v1",
                SemanticBuildStatus.PARTIAL_FAILURE, 2, 1, 0, 1, List.of(),
                started, finished, true, "build-1", "rev-1", SemanticBuildStatus.SUCCESS);
        when(store.latestBuild("p1", "doc", "5.1")).thenReturn(Optional.of(view));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/requirement-semantic/builds/latest")
                        .param("projectId", "p1")
                        .param("documentId", "doc")
                        .param("requirementVersion", "5.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestRunStatus").value("PARTIAL_FAILURE"))
                .andExpect(jsonPath("$.generationActive").value(true))
                .andExpect(jsonPath("$.activeGenerationStatus").value("SUCCESS"))
                // 旧 SemanticBuildRecord JSON 字段兼容：依赖 buildStatus/active 的调用方不失效。
                .andExpect(jsonPath("$.buildStatus").value("PARTIAL_FAILURE"))
                .andExpect(jsonPath("$.active").value(true))
                // #8（Review 中）：时间字段兼容——旧调用方按 startedAt/finishedAt 轮询，不能因为
                // DTO 改名 runStartedAt/runFinishedAt 就读到 undefined。
                .andExpect(jsonPath("$.runStartedAt").value("2026-08-26T07:56:40.227588Z"))
                .andExpect(jsonPath("$.startedAt").value("2026-08-26T07:56:40.227588Z"))
                .andExpect(jsonPath("$.runFinishedAt").value("2026-08-26T07:56:40.227590Z"))
                .andExpect(jsonPath("$.finishedAt").value("2026-08-26T07:56:40.227590Z"));
    }

    // ---------------- 聚合构建状态（项目/版本范围，前端状态条同语义检索范围） ----------------

    private SemanticBuildAggregateView aggregateView() {
        return new SemanticBuildAggregateView(
                "p1", "5.1", true, 2,
                List.of("doc-a", "doc-b"), List.of("build-1", "build-2"),
                "run-9", "build-2", SemanticBuildStatus.SUCCESS, 10, 10, 0, List.of(),
                true, true, true);
    }

    @Test
    void aggregateJsonExposesAggregationAndRetrievalFlags() throws Exception {
        stubIdentityResolution();
        // 聚合接口是前端状态条的契约：必须返回代际聚合字段与检索开关，供前端区分
        // "配置关闭"与"召回质量差"（P1），并携带 active 代际身份集合（评测键依赖）。
        when(store.aggregateBuildStatus("p1", "5.1", true, true, true))
                .thenReturn(Optional.of(aggregateView()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/requirement-semantic/builds/aggregate")
                        .param("projectId", "p1")
                        .param("requirementVersion", "5.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("p1"))
                .andExpect(jsonPath("$.requirementVersion").value("5.1"))
                .andExpect(jsonPath("$.hasActiveGeneration").value(true))
                .andExpect(jsonPath("$.activeDocumentCount").value(2))
                .andExpect(jsonPath("$.activeDocumentIds[0]").value("doc-a"))
                .andExpect(jsonPath("$.activeBuildIds[1]").value("build-2"))
                .andExpect(jsonPath("$.latestRunStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.candidateRetrievalEnabled").value(true))
                .andExpect(jsonPath("$.normativeRetrievalEnabled").value(true))
                .andExpect(jsonPath("$.multiSourceEnabledForProject").value(true));
        verify(businessProjects).requireProject("p1");
        // MockMvc 使用的是 MockHttpServletRequest，而非上面的 httpRequest mock。
        verify(accessGuard).requireProjectAccess(org.mockito.ArgumentMatchers.any(HttpServletRequest.class),
                org.mockito.ArgumentMatchers.eq("p1"));
    }

    @Test
    void aggregateEnforcesProjectAccess() {
        stubIdentityResolution();
        // 项目未注册或无权访问时不允许读到聚合状态（它暴露了文档与构建 ID 集合）。
        // standaloneSetup 无 @ControllerAdvice，HTTP 层行为不在此断；直接断言 proxy 不吞异常。
        org.mockito.Mockito.doThrow(new IllegalStateException("denied"))
                .when(accessGuard).requireProjectAccess(httpRequest, "p1");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        controller.aggregateBuild("p1", "5.1", httpRequest))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aggregateEmptyWithoutRuns() throws Exception {
        stubIdentityResolution();
        // 没有任何构建 run 的版本：接口返回空体（200），前端据此进入"未构建"提示，
        // 而非把 404 误判成"模块未启用"之外的情形。
        when(store.aggregateBuildStatus("p1", "5.1", true, true, true)).thenReturn(Optional.empty());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/requirement-semantic/builds/aggregate")
                        .param("projectId", "p1")
                        .param("requirementVersion", "5.1"))
                .andExpect(status().isOk())
                .andExpect(content().string("null"));
    }
}
