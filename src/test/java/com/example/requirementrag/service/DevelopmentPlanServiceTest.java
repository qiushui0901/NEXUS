package com.example.requirementrag.service;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.conflict.KnowledgeConflictModels.ReportStatus;
import com.example.requirementrag.evidence.CitationQualityStatus;
import com.example.requirementrag.evidence.EvidenceSupportStatus;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.QueryRouting;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.observability.RagObservability;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DevelopmentPlanServiceTest {

    private final RagProperties properties = mock(RagProperties.class);
    private final ProjectRegistry projectRegistry = mock(ProjectRegistry.class);
    private final QueryRouter queryRouter = mock(QueryRouter.class);
    private final QdrantHybridStore documentStore = mock(QdrantHybridStore.class);
    private final CodeKnowledgeService codeKnowledgeService = mock(CodeKnowledgeService.class);
    private final ChatClient chatClient = mock(ChatClient.class);
    private final RagObservability observability = mock(RagObservability.class);
    private DevelopmentPlanService service;

    @BeforeEach
    void setUp() {
        RagProperties.Knowledge knowledge = mock(RagProperties.Knowledge.class);
        when(knowledge.documentId()).thenReturn("requirements");
        when(knowledge.version()).thenReturn("5.1");
        when(properties.knowledge()).thenReturn(knowledge);
        when(projectRegistry.resolveRequirementCollection("game")).thenReturn("requirements_game");
        when(queryRouter.routeWithOutcome("query", null)).thenReturn(RagOutcome.of(
                RagOutcomeStatus.SUCCESS, new QueryRouting("game", "server", 1.0, "llm"),
                "query.route", 1, 1));
        service = new DevelopmentPlanService(properties, projectRegistry, queryRouter, documentStore,
                codeKnowledgeService, chatClient, observability);
    }

    @Test
    void distinguishesSuccessfulEmptyRetrievalFromDependencyFailure() {
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenReturn(List.of());
        when(codeKnowledgeService.search("query", "game", 8)).thenReturn(List.of());

        var response = service.plan("query", null, null, null, 8);

        assertEquals(RagOutcomeStatus.NO_RESULTS, response.status());
        assertEquals(List.of(), response.warnings());
        assertEquals(ReportStatus.CLEAR, response.conflictReport().status());
    }

    @Test
    void degradesWhenDocumentSearchFailsButCodeEvidenceExists() {
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenThrow(new RuntimeException("qdrant internal url"));
        when(codeKnowledgeService.search("query", "game", 8)).thenReturn(List.of(codeChunk()));

        var response = service.plan("query", null, null, null, 8);

        assertEquals(RagOutcomeStatus.DEGRADED, response.status());
        assertEquals("DOCUMENT_RETRIEVAL_UNAVAILABLE", response.warnings().getFirst().code());
    }

    @Test
    void failsWhenCodeSearchFailsAndNoDocumentEvidenceExists() {
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenReturn(List.of());
        when(codeKnowledgeService.search("query", "game", 8))
                .thenThrow(new RuntimeException("code store unavailable"));

        RagUnavailableException failure = assertThrows(RagUnavailableException.class,
                () -> service.plan("query", null, null, null, 8));

        assertEquals("CODE_RETRIEVAL_UNAVAILABLE", failure.warnings().getFirst().code());
    }

    @Test
    void treatsNullModelDraftAsDegradedFallback() {
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenReturn(List.of(documentChunk()));
        when(codeKnowledgeService.search("query", "game", 8)).thenReturn(List.of());
        when(properties.llm()).thenReturn(new RagProperties.Llm("generation-model", "reranker", "router", null, null));
        ChatClient nullResultChatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        DevelopmentPlanService nullResultService = new DevelopmentPlanService(properties, projectRegistry, queryRouter,
                documentStore, codeKnowledgeService, nullResultChatClient, observability);

        var response = nullResultService.plan("query", null, null, null, 8);

        assertEquals(RagOutcomeStatus.DEGRADED, response.status());
        assertEquals("PLAN_GENERATION_FALLBACK", response.warnings().getFirst().code());
        assertEquals("模型未返回有效方案，已使用规则化方案", response.warnings().getFirst().message());
        assertEquals(1, response.citations().references().size());
        assertEquals("requirement:doc-1", response.citations().references().getFirst().evidenceId());
        assertEquals(EvidenceSupportStatus.UNSUPPORTED, response.citations().summary().supportStatus());
        assertEquals(CitationQualityStatus.INSUFFICIENT_EVIDENCE, response.citations().quality().status());
        assertEquals(0.0, response.citations().quality().coverageRate());
    }

    @Test
    void marksGenerationFallbackAsDegradedWithoutLeakingProviderError() {
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenReturn(List.of(documentChunk()));
        when(codeKnowledgeService.search("query", "game", 8)).thenReturn(List.of());
        when(chatClient.prompt()).thenThrow(new RuntimeException("https://secret-host/token=abc"));

        var response = service.plan("query", null, null, null, 8);

        assertEquals(RagOutcomeStatus.DEGRADED, response.status());
        assertEquals("PLAN_GENERATION_FALLBACK", response.warnings().getFirst().code());
        assertEquals("模型生成失败，已使用规则化方案", response.warnings().getFirst().message());
    }


    @Test
    void reportsRequirementEvidenceFromAnotherVersionAsBlocked() {
        ChunkRecord stale = new ChunkRecord("doc-old", "requirements", "2.0", "requirements.md", "parent-1",
                "通用规则内容", "规则", "hash-old", 1, 1);
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenReturn(List.of(stale));
        when(codeKnowledgeService.search("query", "game", 8)).thenReturn(List.of());
        when(properties.llm()).thenReturn(new RagProperties.Llm("generation-model", "reranker", "router", null, null));
        ChatClient nullResultChatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        DevelopmentPlanService checkedService = new DevelopmentPlanService(properties, projectRegistry, queryRouter,
                documentStore, codeKnowledgeService, nullResultChatClient, observability);

        var response = checkedService.plan("query", null, null, null, 8);

        assertEquals(ReportStatus.BLOCKED, response.conflictReport().status());
        assertEquals("VERSION_CONTAMINATION", response.conflictReport().conflicts().getFirst().type().name());
    }

    private ChunkRecord documentChunk() {
        return new ChunkRecord("doc-1", "requirements", "5.1", "feature-rules.html", "parent-1",
                "功能按配置条件生效", "功能规则", "hash", 1, 1);
    }

    private CodeChunk codeChunk() {
        return new CodeChunk("code-1", "game", "sha", "service/FeatureRuleService.java", "class",
                "FeatureRuleService", 1, 20, "class FeatureRuleService", "hash");
    }
}
