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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

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
        when(properties.retrieval()).thenReturn(new RagProperties.Retrieval(
                50, 50, 40, 20, 10, false, 1_000, 3, 3, 30_000,
                -1, -1, -1, -1, null, null, null));
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
    void compatibilityConstructorUsesLegacyParentDeduplicationWhenRetrievalConfigIsMissing() {
        when(properties.retrieval()).thenReturn(null);
        ChunkRecord first = chunk("p1", "需求一", "h1");
        ChunkRecord duplicateParent = chunk("p1", "需求一子块", "h2");
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenReturn(List.of(first, duplicateParent));
        when(codeKnowledgeService.search("query", "game", 8)).thenReturn(List.of());

        RagOutcome<RetrievalBundle> outcome = pipeline.execute(new RetrievalRequest(
                "query", RetrievalProfile.DEVELOPMENT_PLAN, null, null, null, 8));

        assertThat(outcome.status()).isEqualTo(RagOutcomeStatus.SUCCESS);
        assertThat(outcome.data().requirementEvidence()).containsExactly(first);
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

    @Test
    void requirementReviewCanLoadVersionCorpusWithoutSearchingCode() {
        ChunkRecord corpus = chunk("corpus", "完整正文", "corpus-hash");
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenReturn(List.of(chunk("hit", "检索命中", "hit-hash")));
        when(documentStore.scrollVersion("requirements_game", "requirements", "5.1"))
                .thenReturn(List.of(corpus));

        RagOutcome<RetrievalBundle> outcome = pipeline.execute(new RetrievalRequest(
                "query", RetrievalProfile.REQUIREMENT_REVIEW, null, "requirements", "5.1", 8, true));

        assertThat(outcome.status()).isEqualTo(RagOutcomeStatus.SUCCESS);
        assertThat(outcome.data().resolvedProjectId()).isEqualTo("game");
        assertThat(outcome.data().requirementCorpus()).containsExactly(corpus);
        assertThat(outcome.stageDiagnostics()).extracting("stage").contains("qdrant.scroll");
        verify(documentStore).scrollVersion("requirements_game", "requirements", "5.1");
        verify(codeKnowledgeService, never()).search(any(), any(), anyInt());
    }

    @Test
    void requirementReviewDegradesWhenHybridSearchFailsButCorpusExists() {
        ChunkRecord corpus = chunk("corpus", "完整正文", "corpus-hash");
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenThrow(new RuntimeException("private endpoint"));
        when(documentStore.scrollVersion("requirements_game", "requirements", "5.1"))
                .thenReturn(List.of(corpus));

        RagOutcome<RetrievalBundle> outcome = pipeline.execute(new RetrievalRequest(
                "query", RetrievalProfile.REQUIREMENT_REVIEW, null, "requirements", "5.1", 8, true));

        assertThat(outcome.status()).isEqualTo(RagOutcomeStatus.DEGRADED);
        assertThat(outcome.data().requirementCorpus()).containsExactly(corpus);
        assertThat(outcome.warnings()).extracting("code").contains("DOCUMENT_RETRIEVAL_UNAVAILABLE");
        assertThat(outcome.warnings().getFirst().message()).doesNotContain("endpoint");
    }

    @Test
    void parallelRecallP95BeatsSequentialBaselineByThirtyPercent() throws Exception {
        final int branchDelayMs = 100;
        final int warmupRuns = 2;
        final int repetitions = 10;
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenAnswer(invocation -> delayed(List.of(chunk("hit", "命中", "h1")), branchDelayMs));
        when(documentStore.scrollVersion("requirements_game", "requirements", "5.1"))
                .thenAnswer(invocation -> delayed(List.of(chunk("corpus", "正文", "h2")), branchDelayMs));
        when(codeKnowledgeService.search("query", "game", 8))
                .thenAnswer(invocation -> delayed(List.of(code("code-1")), branchDelayMs));
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            RetrievalPipeline concurrent = new RetrievalPipeline(properties, projectRegistry, queryRouter,
                    documentStore, codeKnowledgeService, mock(RagObservability.class),
                    RequirementReranker.passthrough(),
                    new RetrievalResultCache(Duration.ZERO, 0, "disabled"), executor,
                    new RetrievalCircuitBreaker(0, Duration.ZERO));
            long sequentialP95 = measureP95(warmupRuns, repetitions, () -> {
                delayed(null, branchDelayMs);
                delayed(null, branchDelayMs);
                delayed(null, branchDelayMs);
            });
            long parallelP95 = measureP95(warmupRuns, repetitions, () -> {
                RagOutcome<RetrievalBundle> outcome = concurrent.execute(new RetrievalRequest(
                        "query", RetrievalProfile.DEVELOPMENT_PLAN, null, null, null, 8, true));
                assertThat(outcome.status()).isEqualTo(RagOutcomeStatus.SUCCESS);
            });
            double reduction = 1.0 - ((double) parallelP95 / sequentialP95);
            boolean passed = reduction + 1e-12 >= 0.30;

            writeParallelBenchmark(branchDelayMs, warmupRuns, repetitions, sequentialP95, parallelP95,
                    reduction, passed);

            assertThat(sequentialP95).isGreaterThanOrEqualTo(branchDelayMs * 3L);
            assertThat(reduction).isGreaterThanOrEqualTo(0.30);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reranksChildCandidatesBeforeAggregatingParents() {
        ChunkRecord parentAFirst = new ChunkRecord("a-1", "requirements", "5.1", "feature.md",
                "parent-a", "parent A", "irrelevant child", "h1", 0, 0);
        ChunkRecord parentARelevant = new ChunkRecord("a-2", "requirements", "5.1", "feature.md",
                "parent-a", "parent A", "relevant child", "h2", 0, 1);
        ChunkRecord parentB = new ChunkRecord("b-1", "requirements", "5.1", "other.md",
                "parent-b", "parent B", "other child", "h3", 1, 0);
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenReturn(List.of(parentAFirst, parentARelevant, parentB));
        when(codeKnowledgeService.search("query", "game", 8)).thenReturn(List.of());
        RequirementReranker reranker = (query, documentId, version, candidates, limit) -> {
            assertThat(candidates).containsExactly(parentAFirst, parentARelevant, parentB);
            assertThat(limit).isEqualTo(20);
            return RagOutcome.of(RagOutcomeStatus.SUCCESS,
                    List.of(parentARelevant, parentAFirst, parentB), "retrieval.rerank", 1, 3);
        };
        RetrievalPipeline childFirst = new RetrievalPipeline(properties, projectRegistry, queryRouter,
                documentStore, codeKnowledgeService, mock(RagObservability.class), reranker,
                new RetrievalResultCache(Duration.ZERO, 0, "disabled"), Runnable::run,
                new RetrievalCircuitBreaker(0, Duration.ZERO));

        RagOutcome<RetrievalBundle> outcome = childFirst.execute(new RetrievalRequest(
                "query", RetrievalProfile.DEVELOPMENT_PLAN, null, null, null, 8));

        assertThat(outcome.data().requirementEvidence()).containsExactly(parentARelevant, parentB);
    }

    @Test
    void avoidsRedundantChildScoresWhenAllCandidatesShareOneParent() {
        ChunkRecord first = new ChunkRecord("a-1", "requirements", "5.1", "feature.md",
                "parent-a", "parent A", "first child", "h1", 0, 0);
        ChunkRecord second = new ChunkRecord("a-2", "requirements", "5.1", "feature.md",
                "parent-a", "parent A", "second child", "h2", 0, 1);
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenReturn(List.of(first, second));
        when(codeKnowledgeService.search("query", "game", 8)).thenReturn(List.of());
        RequirementReranker reranker = (query, documentId, version, candidates, limit) -> {
            assertThat(candidates).containsExactly(first);
            return RagOutcome.of(RagOutcomeStatus.SUCCESS, candidates, "retrieval.rerank", 1, candidates.size());
        };
        RetrievalPipeline childFirst = new RetrievalPipeline(properties, projectRegistry, queryRouter,
                documentStore, codeKnowledgeService, mock(RagObservability.class), reranker,
                new RetrievalResultCache(Duration.ZERO, 0, "disabled"), Runnable::run,
                new RetrievalCircuitBreaker(0, Duration.ZERO));

        RagOutcome<RetrievalBundle> outcome = childFirst.execute(new RetrievalRequest(
                "query", RetrievalProfile.DEVELOPMENT_PLAN, null, null, null, 8));

        assertThat(outcome.data().requirementEvidence()).containsExactly(first);
        assertThat(outcome.stageDiagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.stage()).isEqualTo("retrieval.requirement.candidates");
                    assertThat(diagnostic.itemCount()).isEqualTo(2);
                })
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.stage()).isEqualTo("retrieval.requirement.rerank_candidates");
                    assertThat(diagnostic.itemCount()).isEqualTo(1);
                });
    }

    @Test
    void appliesUnifiedRerankerToEveryProfile() {
        RequirementReranker reranker = mock(RequirementReranker.class);
        ChunkRecord hit = chunk("hit", "需求", "h1");
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenReturn(List.of(hit));
        when(codeKnowledgeService.search("query", "game", 8)).thenReturn(List.of());
        when(reranker.rerank(any(), any(), any(), any(), anyInt()))
                .thenReturn(RagOutcome.of(RagOutcomeStatus.SUCCESS, List.of(hit), "retrieval.rerank", 1, 1));
        RetrievalPipeline unified = new RetrievalPipeline(properties, projectRegistry, queryRouter,
                documentStore, codeKnowledgeService, mock(RagObservability.class), reranker,
                new RetrievalResultCache(Duration.ZERO, 0, "disabled"), Runnable::run,
                new RetrievalCircuitBreaker(0, Duration.ZERO));

        for (RetrievalProfile profile : RetrievalProfile.values()) {
            unified.execute(new RetrievalRequest("query", profile, null, null, null, 8));
        }

        verify(reranker, times(3)).rerank(any(), any(), any(), any(), anyInt());
    }

    @Test
    void timesOutOnlyTheSlowBranchAndKeepsAvailableEvidence() throws Exception {
        when(properties.retrieval()).thenReturn(new RagProperties.Retrieval(
                50, 50, 40, 20, 10, false, 50, 2, 3, 30_000,
                -1, -1, -1, -1, null, null, null));
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenReturn(List.of(chunk("hit", "需求", "h1")));
        when(codeKnowledgeService.search("query", "game", 8)).thenAnswer(invocation -> {
            Thread.sleep(250);
            return List.of(code("late"));
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        RetrievalCircuitBreaker breaker = new RetrievalCircuitBreaker(1, Duration.ofSeconds(10));
        try {
            RetrievalPipeline timeoutPipeline = new RetrievalPipeline(properties, projectRegistry, queryRouter,
                    documentStore, codeKnowledgeService, mock(RagObservability.class),
                    RequirementReranker.passthrough(),
                    new RetrievalResultCache(Duration.ZERO, 0, "disabled"), executor,
                    breaker);

            RagOutcome<RetrievalBundle> outcome = timeoutPipeline.execute(new RetrievalRequest(
                    "query", RetrievalProfile.DEVELOPMENT_PLAN, null, null, null, 8));

            assertThat(outcome.status()).isEqualTo(RagOutcomeStatus.DEGRADED);
            assertThat(outcome.data().requirementEvidence()).hasSize(1);
            assertThat(outcome.data().codeEvidence()).isEmpty();
            assertThat(outcome.warnings()).extracting("code").contains("CODE_RETRIEVAL_TIMEOUT");
            Thread.sleep(300);
            assertThat(breaker.allow("code.hybrid_search")).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void resultCacheIncludesVersionScopeAndAvoidsDuplicateRetrieval() {
        ChunkRecord v51 = chunk("hit", "5.1 需求", "h1");
        ChunkRecord v52 = new ChunkRecord("v52-child", "requirements", "5.2", "5.2/feature.html",
                "v52", "5.2 需求", "5.2 需求", "h2", 1, 1);
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenReturn(List.of(v51));
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.2"))
                .thenReturn(List.of(v52));
        RetrievalPipeline cached = new RetrievalPipeline(properties, projectRegistry, queryRouter,
                documentStore, codeKnowledgeService, mock(RagObservability.class),
                RequirementReranker.passthrough(),
                new RetrievalResultCache(Duration.ofMinutes(1), 10, "test"), Runnable::run,
                new RetrievalCircuitBreaker(0, Duration.ZERO));

        cached.execute(new RetrievalRequest("query", RetrievalProfile.REQUIREMENT_REVIEW,
                null, "requirements", "5.1", 8));
        cached.execute(new RetrievalRequest("query", RetrievalProfile.REQUIREMENT_REVIEW,
                null, "requirements", "5.1", 8));
        RagOutcome<RetrievalBundle> otherVersion = cached.execute(new RetrievalRequest(
                "query", RetrievalProfile.REQUIREMENT_REVIEW, null, "requirements", "5.2", 8));

        verify(documentStore, times(1))
                .hybridSearch("requirements_game", "query", "requirements", "5.1");
        verify(documentStore, times(1))
                .hybridSearch("requirements_game", "query", "requirements", "5.2");
        assertThat(otherVersion.data().requirementEvidence()).containsExactly(v52);
    }

    private long measureP95(int warmupRuns, int repetitions, ThrowingRunnable action) throws Exception {
        for (int iteration = 0; iteration < warmupRuns; iteration++) {
            action.run();
        }
        List<Long> samples = new ArrayList<>();
        for (int iteration = 0; iteration < repetitions; iteration++) {
            long begin = System.nanoTime();
            action.run();
            samples.add(Duration.ofNanos(System.nanoTime() - begin).toMillis());
        }
        samples.sort(Long::compareTo);
        return samples.get((int) Math.ceil(samples.size() * 0.95) - 1);
    }

    private void writeParallelBenchmark(int branchDelayMs, int warmupRuns, int repetitions,
                                        long sequentialP95, long parallelP95, double reduction,
                                        boolean passed) throws IOException {
        Path output = Path.of(System.getenv().getOrDefault(
                "RETRIEVAL_PARALLEL_BENCHMARK_OUTPUT",
                "target/retrieval-evaluation/parallel-recall-benchmark.json"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        String json = """
                {
                  "schemaVersion": 1,
                  "classification": "controlled-fake-dependency",
                  "profile": "DEVELOPMENT_PLAN",
                  "branchCount": 3,
                  "branchDelayMs": %d,
                  "warmupRuns": %d,
                  "repetitions": %d,
                  "sequentialP95Ms": %d,
                  "parallelP95Ms": %d,
                  "reduction": %.6f,
                  "requiredReduction": 0.30,
                  "passed": %s
                }
                """.formatted(branchDelayMs, warmupRuns, repetitions, sequentialP95, parallelP95,
                reduction, passed);
        Files.writeString(output, json, StandardCharsets.UTF_8);
    }

    private <T> T delayed(T value) throws InterruptedException {
        return delayed(value, 100);
    }

    private <T> T delayed(T value, int delayMs) throws InterruptedException {
        Thread.sleep(delayMs);
        return value;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
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
