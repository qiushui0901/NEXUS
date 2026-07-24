package com.example.requirementrag.retrieval.pipeline;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.QueryRouting;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.observability.RagObservability;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.service.QueryRouter;
import com.example.requirementrag.service.RagUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalPipelineTest {
    private final RagProperties properties = mock(RagProperties.class);
    private final ProjectRegistry projectRegistry = mock(ProjectRegistry.class);
    private final QueryRouter queryRouter = mock(QueryRouter.class);
    private final QdrantHybridStore documentStore = mock(QdrantHybridStore.class);
    private final CodeKnowledgeService codeKnowledgeService = mock(CodeKnowledgeService.class);
    private RetrievalPipeline pipeline;

    @BeforeEach
    void setUp() {
        when(properties.knowledge()).thenReturn(new RagProperties.Knowledge(
                false, null, null, "requirements", "5.1", null, null, 0));
        when(queryRouter.routeWithOutcome("query", null)).thenReturn(RagOutcome.of(
                RagOutcomeStatus.SUCCESS, new QueryRouting("game", "server", 1.0, "explicit"),
                "query.route", 1, 1));
        when(projectRegistry.resolveRequirementCollection("game")).thenReturn("requirements_game");
        pipeline = new RetrievalPipeline(properties, projectRegistry, queryRouter, documentStore,
                codeKnowledgeService, mock(RagObservability.class));
    }

    @Test
    void returnsDeduplicatedEvidenceAndSuccess() {
        ChunkRecord first = chunk("p1", "需求一", "h1");
        ChunkRecord duplicateParent = chunk("p1", "需求一子块", "h2");
        CodeChunk code = code("code-1");
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenReturn(List.of(first, duplicateParent));
        when(codeKnowledgeService.search("query", "game", 8)).thenReturn(List.of(code, code));

        RagOutcome<RetrievalBundle> outcome = pipeline.execute(new RetrievalRequest(
                "query", RetrievalProfile.DEVELOPMENT_PLAN, null, null, null, 8));

        assertThat(outcome.status()).isEqualTo(RagOutcomeStatus.SUCCESS);
        assertThat(outcome.data().requirementEvidence()).containsExactly(first);
        assertThat(outcome.data().codeEvidence()).containsExactly(code);
        assertThat(outcome.stageDiagnostics()).extracting("stage")
                .contains("query.route", "qdrant.hybrid_search", "code.hybrid_search");
    }

    @Test
    void distinguishesNoResultsFromDependencyFailure() {
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenReturn(List.of());
        when(codeKnowledgeService.search("query", "game", 8)).thenReturn(List.of());

        RagOutcome<RetrievalBundle> outcome = pipeline.execute(new RetrievalRequest(
                "query", RetrievalProfile.DEVELOPMENT_PLAN, null, null, null, 8));

        assertThat(outcome.status()).isEqualTo(RagOutcomeStatus.NO_RESULTS);
        assertThat(outcome.warnings()).isEmpty();
    }

    @Test
    void degradesWhenOneSourceFailsButOtherEvidenceExists() {
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenThrow(new RuntimeException("internal qdrant url"));
        when(codeKnowledgeService.search("query", "game", 8)).thenReturn(List.of(code("code-1")));

        RagOutcome<RetrievalBundle> outcome = pipeline.execute(new RetrievalRequest(
                "query", RetrievalProfile.DEVELOPMENT_PLAN, null, null, null, 8));

        assertThat(outcome.status()).isEqualTo(RagOutcomeStatus.DEGRADED);
        assertThat(outcome.warnings()).extracting("code").containsExactly("DOCUMENT_RETRIEVAL_UNAVAILABLE");
        assertThat(outcome.warnings().getFirst().message()).doesNotContain("qdrant");
    }

    @Test
    void failsWhenCoreSourceFailsAndThereIsNoEvidence() {
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenReturn(List.of());
        when(codeKnowledgeService.search("query", "game", 8)).thenThrow(new RuntimeException("secret"));

        assertThatThrownBy(() -> pipeline.execute(new RetrievalRequest(
                "query", RetrievalProfile.DEVELOPMENT_PLAN, null, null, null, 8)))
                .isInstanceOf(RagUnavailableException.class)
                .hasMessageNotContaining("secret");
    }

    @Test
    void requirementReviewProfileDoesNotSearchCode() {
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenReturn(List.of(chunk("p1", "需求", "h1")));

        RagOutcome<RetrievalBundle> outcome = pipeline.execute(new RetrievalRequest(
                "query", RetrievalProfile.REQUIREMENT_REVIEW, null, null, null, 8));

        assertThat(outcome.status()).isEqualTo(RagOutcomeStatus.SUCCESS);
        verify(codeKnowledgeService, never()).search("query", "game", 8);
    }

    private ChunkRecord chunk(String parentId, String text, String hash) {
        return new ChunkRecord(parentId + "-child", "requirements", "5.1", "feature.html",
                parentId, text, text, hash, 1, 1);
    }

    private CodeChunk code(String id) {
        return new CodeChunk(id, "game", "sha", "src/FeatureService.java", "METHOD", "run",
                10, 20, "void run() {}", "hash");
    }
}
