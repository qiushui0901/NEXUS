package com.example.requirementrag.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.knowledge.KnowledgeBootstrapService;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.BaseType;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.ChunkView;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.EntityStatus;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.KnowledgeBaseView;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.RetrievalTestRequest;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.RunView;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.SourceType;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.Stage;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.SummaryStatus;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.TriggerType;
import com.example.requirementrag.knowledge.management.SQLiteKnowledgeManagementStore;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.RagStageDiagnostic;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.model.UserRole;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalPipeline;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeManagementControllerTest {
    private SQLiteKnowledgeManagementStore store;
    private ProjectRegistry projectRegistry;
    private ProjectAccessGuard accessGuard;
    private KnowledgeBootstrapService bootstrapService;
    private RetrievalPipeline retrievalPipeline;
    private QdrantHybridStore qdrantStore;
    private KnowledgeManagementController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = mock(SQLiteKnowledgeManagementStore.class);
        projectRegistry = mock(ProjectRegistry.class);
        accessGuard = mock(ProjectAccessGuard.class);
        bootstrapService = mock(KnowledgeBootstrapService.class);
        retrievalPipeline = mock(RetrievalPipeline.class);
        qdrantStore = mock(QdrantHybridStore.class);
        controller = new KnowledgeManagementController(
                store, projectRegistry, accessGuard, bootstrapService, retrievalPipeline, qdrantStore);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void validatesAccessUsingKnowledgeBaseProjectAndRebuildsThatProject() throws Exception {
        when(store.requireBase("orders:requirement")).thenReturn(base("orders:requirement", "orders"));

        mvc.perform(post("/api/knowledge-bases/orders:requirement/rebuild"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.mode").value("PROJECT_REBUILD"))
                .andExpect(jsonPath("$.projectId").value("orders"));

        verify(projectRegistry).require("orders");
        verify(accessGuard).requireProjectAccess(any(HttpServletRequest.class), eq("orders"));
        verify(bootstrapService).bootstrapAsync("orders");
    }

    @Test
    void returnsNotFoundWhenRunDoesNotBelongToKnowledgeBase() throws Exception {
        when(store.requireBase("orders:requirement")).thenReturn(base("orders:requirement", "orders"));
        when(store.requireRun("orders:requirement", "foreign-run"))
                .thenThrow(new IllegalArgumentException("知识管理资源不存在"));

        mvc.perform(get("/api/knowledge-bases/orders:requirement/runs/foreign-run"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("知识管理资源不存在"));
    }

    @Test
    void retriesChunkAsDocumentRebuildOnlyAfterScopedLookup() throws Exception {
        when(store.requireBase("orders:requirement")).thenReturn(base("orders:requirement", "orders"));
        when(store.requireChunkInBase("orders:requirement", "chunk-1")).thenReturn(chunk("chunk-1"));

        mvc.perform(post("/api/knowledge-bases/orders:requirement/chunks/chunk-1/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.mode").value("DOCUMENT_REBUILD"));

        verify(store).requireChunkInBase("orders:requirement", "chunk-1");
        verify(bootstrapService).bootstrapAsync("orders");
    }

    @Test
    void rejectsBlankRetrievalQueryBeforeCallingPipeline() throws Exception {
        when(store.requireBase("orders:requirement")).thenReturn(base("orders:requirement", "orders"));

        mvc.perform(post("/api/knowledge-bases/orders:requirement/retrieval-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verify(retrievalPipeline, never()).execute(any());
    }

    @Test
    void preservesRetrievalOrderWarningsAndDiagnosticsWhileSanitizingPayload() throws Exception {
        when(store.requireBase("orders:requirement")).thenReturn(base("orders:requirement", "orders"));
        ChunkRecord first = new ChunkRecord("chunk-b", "requirements", "2.0",
                "/Users/user/private/rules/order.html", "parent-b",
                "P".repeat(1300), "C".repeat(700), "hash-b", 2, 1);
        ChunkRecord second = new ChunkRecord("chunk-a", "requirements", "2.0",
                "rules/payment.html", "parent-a",
                "付款规则", "支付超时", "hash-a", 1, 0);
        RagWarning warning = new RagWarning("requirement-rerank", "RERANK_DEGRADED",
                "重排不可用，已保留召回顺序", 12);
        RagStageDiagnostic diagnostic = new RagStageDiagnostic(
                "requirement-retrieval", RagOutcomeStatus.DEGRADED, 20, 2);
        RetrievalBundle bundle = new RetrievalBundle(
                "订单失败", RetrievalProfile.REQUIREMENT_REVIEW, "orders",
                "requirements", "2.0", List.of(first, second), List.of());
        when(retrievalPipeline.execute(any())).thenReturn(new RagOutcome<>(
                RagOutcomeStatus.DEGRADED, bundle, List.of(warning), List.of(diagnostic)));

        String response = mvc.perform(post("/api/knowledge-bases/orders:requirement/retrieval-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"订单失败\",\"limit\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.hits[0].rank").value(1))
                .andExpect(jsonPath("$.hits[0].chunkId").value("chunk-b"))
                .andExpect(jsonPath("$.hits[0].sourcePath").value("order.html"))
                .andExpect(jsonPath("$.hits[1].chunkId").value("chunk-a"))
                .andExpect(jsonPath("$.warnings[0].code").value("RERANK_DEGRADED"))
                .andExpect(jsonPath("$.stageDiagnostics[0].itemCount").value(2))
                .andReturn().getResponse().getContentAsString();

        assertThat(response)
                .doesNotContain("vector")
                .doesNotContain("exception")
                .doesNotContain("/Users/user");
        JsonNode json = new ObjectMapper().readTree(response);
        assertThat(json.at("/hits/0/childText").asText()).hasSize(600);
        assertThat(json.at("/hits/0/parentText").asText()).hasSize(1200);
        ArgumentCaptor<com.example.requirementrag.retrieval.pipeline.RetrievalRequest> request =
                ArgumentCaptor.forClass(com.example.requirementrag.retrieval.pipeline.RetrievalRequest.class);
        verify(retrievalPipeline).execute(request.capture());
        assertThat(request.getValue().profile()).isEqualTo(RetrievalProfile.REQUIREMENT_REVIEW);
        assertThat(request.getValue().projectId()).isEqualTo("orders");
    }

    @Test
    void listsOnlyProjectsAccessibleToCurrentUser() throws Exception {
        RagProperties.ProjectConfig orders = mock(RagProperties.ProjectConfig.class);
        RagProperties.ProjectConfig payments = mock(RagProperties.ProjectConfig.class);
        when(orders.id()).thenReturn("orders");
        when(payments.id()).thenReturn("payments");
        when(projectRegistry.all()).thenReturn(List.of(orders, payments));
        when(accessGuard.currentUser(any())).thenReturn(
                new UserContext("reader", UserRole.READONLY, List.of("orders")));
        when(store.listBasesForProjects(List.of("orders"), null, null, null, 0, 10_000))
                .thenReturn(new com.example.requirementrag.knowledge.management.KnowledgeManagementModels.Page<>(
                        List.of(base("orders:requirement", "orders")), 0, 50, 1));

        mvc.perform(get("/api/knowledge-bases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].projectId").value("orders"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("payments"))));
    }

    @Test
    void declaresReadAndWritePermissions() throws Exception {
        assertThat(KnowledgeManagementController.class
                .getMethod("get", String.class, HttpServletRequest.class)
                .getAnnotation(RequiresPermission.class).value()).isEqualTo(Permission.PUBLIC_READ);
        assertThat(KnowledgeManagementController.class
                .getMethod("rebuild", String.class, HttpServletRequest.class)
                .getAnnotation(RequiresPermission.class).value()).isEqualTo(Permission.WRITE);
        assertThat(KnowledgeManagementController.class
                .getMethod("testRetrieval", String.class, RetrievalTestRequest.class, HttpServletRequest.class)
                .getAnnotation(RequiresPermission.class).value()).isEqualTo(Permission.PUBLIC_READ);
    }

    @Test
    void listShowsExistingQdrantKnowledgeWhenStateStoreIsEmpty() throws Exception {
        RagProperties.ProjectConfig orders = mock(RagProperties.ProjectConfig.class);
        when(orders.id()).thenReturn("orders");
        when(orders.name()).thenReturn("订单需求");
        when(orders.requirementCollection()).thenReturn("requirement_chunks");
        when(projectRegistry.all()).thenReturn(List.of(orders));
        when(accessGuard.currentUser(any())).thenReturn(UserContext.defaultAdmin());
        when(store.listBasesForProjects(List.of("orders"), null, null, null, 0, 10_000))
                .thenReturn(new com.example.requirementrag.knowledge.management.KnowledgeManagementModels.Page<>(
                        List.of(), 0, 50, 0));
        when(qdrantStore.countPointsIfAvailable("requirement_chunks")).thenReturn(81L);

        mvc.perform(get("/api/knowledge-bases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].projectId").value("orders"))
                .andExpect(jsonPath("$.items[0].status").value("READY"))
                .andExpect(jsonPath("$.items[0].chunkCount").value(81));
    }

    @Test
    void getFallsBackToQdrantBackedSyntheticBaseWhenStateStoreMissing() throws Exception {
        RagProperties.ProjectConfig orders = mock(RagProperties.ProjectConfig.class);
        when(orders.id()).thenReturn("orders");
        when(orders.name()).thenReturn("订单需求");
        when(orders.requirementCollection()).thenReturn("requirement_chunks");
        when(store.requireBase("orders:requirement"))
                .thenThrow(new IllegalArgumentException("knowledge base not found"));
        when(projectRegistry.find("orders")).thenReturn(Optional.of(orders));
        when(qdrantStore.countPointsIfAvailable("requirement_chunks")).thenReturn(81L);

        mvc.perform(get("/api/knowledge-bases/orders:requirement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("orders"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.chunkCount").value(81));
    }

    @Test
    void listShowsCodeIndexWhenStateStoreEmptyAndTypeFilterIsCode() throws Exception {
        RagProperties.ProjectConfig orders = mock(RagProperties.ProjectConfig.class);
        when(orders.id()).thenReturn("orders");
        when(orders.name()).thenReturn("订单需求");
        when(orders.requirementCollection()).thenReturn("requirement_chunks");
        when(orders.codeCollection()).thenReturn("code_chunks");
        when(projectRegistry.all()).thenReturn(List.of(orders));
        when(accessGuard.currentUser(any())).thenReturn(UserContext.defaultAdmin());
        when(store.listBasesForProjects(List.of("orders"), null, BaseType.CODE, null, 0, 10_000))
                .thenReturn(new com.example.requirementrag.knowledge.management.KnowledgeManagementModels.Page<>(
                        List.of(), 0, 50, 0));
        when(qdrantStore.countPointsIfAvailable("code_chunks")).thenReturn(567L);

        mvc.perform(get("/api/knowledge-bases").param("type", "CODE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].projectId").value("orders"))
                .andExpect(jsonPath("$.items[0].type").value("CODE"))
                .andExpect(jsonPath("$.items[0].chunkCount").value(567));
    }

    @Test
    void getFallsBackToCodeIndexWhenStateStoreMissing() throws Exception {
        RagProperties.ProjectConfig orders = mock(RagProperties.ProjectConfig.class);
        when(orders.id()).thenReturn("orders");
        when(orders.name()).thenReturn("订单需求");
        when(orders.codeCollection()).thenReturn("code_chunks");
        when(store.requireBase("orders:code"))
                .thenThrow(new IllegalArgumentException("knowledge base not found"));
        when(projectRegistry.find("orders")).thenReturn(Optional.of(orders));
        when(qdrantStore.countPointsIfAvailable("code_chunks")).thenReturn(567L);

        mvc.perform(get("/api/knowledge-bases/orders:code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("orders"))
                .andExpect(jsonPath("$.type").value("CODE"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.chunkCount").value(567));
    }

    private KnowledgeBaseView base(String id, String projectId) {
        Instant now = Instant.parse("2026-08-17T00:00:00Z");
        return new KnowledgeBaseView(id, projectId, "订单需求",
                com.example.requirementrag.knowledge.management.KnowledgeManagementModels.BaseType.REQUIREMENT,
                "requirements_" + projectId, SourceType.ZIP, SummaryStatus.READY,
                "2.0", "2.0", 1, 1, 0, 2, now, now, now);
    }

    private ChunkView chunk(String id) {
        return new ChunkView(id, "doc-1", "run-1", "parent-1",
                1, 0, "hash", EntityStatus.FAILED, Stage.INDEX,
                false, false, false, 0, null, null);
    }
}
