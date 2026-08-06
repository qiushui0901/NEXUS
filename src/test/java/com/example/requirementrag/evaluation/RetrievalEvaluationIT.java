package com.example.requirementrag.evaluation;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.code.CodeQdrantStore;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagStageDiagnostic;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.retrieval.pipeline.DefaultRequirementReranker;
import com.example.requirementrag.retrieval.pipeline.RequirementReranker;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalPipeline;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@SpringBootTest(properties = {
        "logging.structured.format.console=",
        "management.tracing.sampling.probability=0",
        "app.rag.knowledge.bootstrap-enabled=false",
        "app.rag.auth.enabled=false"
})
@Import(RetrievalEvaluationIT.EvaluationRerankerConfiguration.class)
@EnabledIfEnvironmentVariable(named = "RUN_RETRIEVAL_EVAL", matches = "(?i)true")
class RetrievalEvaluationIT {

    private static final RetrievalEvaluationSettings SETTINGS = RetrievalEvaluationSettings.fromEnvironment();

    @org.springframework.test.context.DynamicPropertySource
    static void evaluationProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        if (SETTINGS.mode() == RetrievalEvaluationSettings.EvaluationMode.BASELINE_0_7) {
            registry.add("app.rag.retrieval.code-bge-rerank-enabled", () -> "false");
        }
    }

    @Autowired
    private RetrievalPipeline retrievalPipeline;

    @Autowired
    private ProjectRegistry projectRegistry;

    @Autowired
    private QdrantHybridStore documentStore;

    @Autowired
    private CodeKnowledgeService codeKnowledgeService;

    @Autowired
    private TracingRequirementReranker tracingRequirementReranker;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void generatesFrozenPipelineEvaluation() throws Exception {
        List<RetrievalEvaluationCase> dataset =
                RetrievalEvaluationDataset.loadResource(SETTINGS.datasetResource());

        for (int warmup = 0; warmup < SETTINGS.warmupRuns(); warmup++) {
            for (RetrievalEvaluationCase evaluationCase : dataset) {
                retrieve(evaluationCase, false);
            }
        }
        tracingRequirementReranker.clear();

        List<RetrievalEvaluationMatcher.CaseResult> results = new ArrayList<>();
        for (int repetition = 1; repetition <= SETTINGS.repetitions(); repetition++) {
            for (RetrievalEvaluationCase evaluationCase : dataset) {
                EvaluationRetrieval retrieval = retrieve(evaluationCase, true);
                results.add(RetrievalEvaluationMatcher.evaluate(
                        evaluationCase,
                        retrieval.bundle().requirementEvidence(),
                        retrieval.bundle().codeEvidence(),
                        retrieval.documentLatencyMs(),
                        retrieval.codeLatencyMs(),
                        retrieval.totalLatencyMs(),
                        retrieval.documentError(),
                        retrieval.codeError(),
                        repetition,
                        retrieval.warnings(),
                        retrieval.diagnostics(),
                        retrieval.trace()));
            }
        }

        RetrievalEvaluationReport report = RetrievalEvaluationReport.create(
                SETTINGS.datasetResource(), SETTINGS.mode().id(), SETTINGS.warmupRuns(),
                SETTINGS.repetitions(), results);
        report.write(SETTINGS.outputDirectory(), objectMapper);

        System.out.printf(
                "Retrieval report written to %s (mode: %s, failed: %d/%d, infrastructure failures: %d, BGE calls: %d)%n",
                SETTINGS.outputDirectory().toAbsolutePath(), SETTINGS.mode().id(),
                report.summary().failedCases(), report.summary().totalCases(),
                report.summary().infrastructureFailureCases(), report.summary().bgeCalls());

        if (report.summary().infrastructureFailureCases() > 0) {
            throw new AssertionError("Retrieval evaluation had "
                    + report.summary().infrastructureFailureCases()
                    + " infrastructure failure case(s); inspect "
                    + SETTINGS.outputDirectory().resolve("report.md"));
        }
        if (SETTINGS.mode() == RetrievalEvaluationSettings.EvaluationMode.BASELINE_0_7
                && report.summary().bgeCalls() != 0) {
            throw new AssertionError("0.7 baseline must not call the BGE reranker");
        }
        if (SETTINGS.mode() != RetrievalEvaluationSettings.EvaluationMode.BASELINE_0_7
                && report.summary().bgeCalls() == 0
                && report.summary().bgeSingletonSkips() == 0) {
            throw new AssertionError(SETTINGS.mode().id()
                    + " evaluation neither exercised BGE nor recorded safe singleton skips");
        }
        if (SETTINGS.mode() != RetrievalEvaluationSettings.EvaluationMode.BASELINE_0_7
                && report.summary().bgeCalls()
                + report.summary().bgeNoCandidateSkips()
                + report.summary().bgeSingletonSkips() != report.summary().totalCases()) {
            throw new AssertionError(SETTINGS.mode().id()
                    + " evaluation did not account for every BGE decision");
        }
        if (report.cases().stream().anyMatch(result -> !result.success()
                && result.failureAttributions().isEmpty())) {
            throw new AssertionError("Every failed retrieval case must have a stage-level failure attribution");
        }
    }

    private EvaluationRetrieval retrieve(RetrievalEvaluationCase evaluationCase, boolean collectTrace) {
        RetrievalProfile profile = productionProfile(evaluationCase.profile());
        long started = System.nanoTime();
        try {
            RagOutcome<RetrievalBundle> outcome = retrievalPipeline.execute(new RetrievalRequest(
                    evaluationCase.query(), profile, evaluationCase.projectId(),
                    evaluationCase.documentId(), evaluationCase.version(),
                    RetrievalEvaluationMatcher.DEFAULT_CUTOFF));
            long totalLatency = elapsedMillis(started);
            RetrievalBundle bundle = outcome.data() == null
                    ? emptyBundle(evaluationCase, profile)
                    : outcome.data();
            RequirementTrace rerankTrace = tracingRequirementReranker.take(evaluationCase.query());
            RetrievalEvaluationMatcher.EvaluationTrace trace = collectTrace
                    ? collectTrace(evaluationCase, profile, rerankTrace)
                    : RetrievalEvaluationMatcher.EvaluationTrace.empty();
            return new EvaluationRetrieval(
                    bundle,
                    stageLatency(outcome.stageDiagnostics(), false),
                    profile.usesCodeEvidence() ? stageLatency(outcome.stageDiagnostics(), true) : 0,
                    totalLatency,
                    null,
                    null,
                    outcome.warnings(),
                    outcome.stageDiagnostics(),
                    trace);
        } catch (RuntimeException exception) {
            long latency = elapsedMillis(started);
            String error = safeError(exception);
            tracingRequirementReranker.take(evaluationCase.query());
            return new EvaluationRetrieval(
                    emptyBundle(evaluationCase, profile),
                    latency,
                    profile.usesCodeEvidence() ? latency : 0,
                    latency,
                    error,
                    profile.usesCodeEvidence() ? error : null,
                    List.of(),
                    List.of(),
                    RetrievalEvaluationMatcher.EvaluationTrace.empty());
        }
    }

    private RetrievalEvaluationMatcher.EvaluationTrace collectTrace(
            RetrievalEvaluationCase evaluationCase,
            RetrievalProfile profile,
            RequirementTrace rerankTrace) {
        boolean documentTraceAvailable = false;
        boolean codeTraceAvailable = false;
        List<ChunkRecord> rawDocuments = List.of();
        CodeQdrantStore.CodeSearchTrace codeTrace = new CodeQdrantStore.CodeSearchTrace(List.of(), List.of());

        if (profile.usesRequirementEvidence()) {
            try {
                String collection = projectRegistry.resolveRequirementCollection(evaluationCase.projectId());
                rawDocuments = documentStore.hybridSearch(collection, evaluationCase.query(),
                        evaluationCase.documentId(), evaluationCase.version());
                documentTraceAvailable = true;
            } catch (RuntimeException ignored) {
                documentTraceAvailable = false;
            }
        }
        if (profile.usesCodeEvidence()) {
            try {
                codeTrace = codeKnowledgeService.searchTrace(evaluationCase.query(), evaluationCase.projectId(),
                        RetrievalEvaluationMatcher.DEFAULT_CUTOFF);
                codeTraceAvailable = true;
            } catch (RuntimeException ignored) {
                codeTraceAvailable = false;
            }
        }

        RequirementTrace safeRerankTrace = rerankTrace == null ? RequirementTrace.empty() : rerankTrace;
        return new RetrievalEvaluationMatcher.EvaluationTrace(
                documentTraceAvailable,
                rawDocuments,
                safeRerankTrace.candidates(),
                safeRerankTrace.reranked(),
                codeTraceAvailable,
                codeTrace.candidates(),
                codeTrace.ranked(),
                codeTrace.denseCandidates(),
                codeTrace.sparseCandidates());
    }

    private RetrievalProfile productionProfile(RetrievalEvaluationCase.RetrievalProfile profile) {
        return switch (profile) {
            case DEVELOPMENT_PLAN -> RetrievalProfile.DEVELOPMENT_PLAN;
            case REQUIREMENT_REVIEW -> RetrievalProfile.REQUIREMENT_REVIEW;
            case WIKI_BUILD -> RetrievalProfile.WIKI_BUILD;
        };
    }

    private RetrievalBundle emptyBundle(RetrievalEvaluationCase evaluationCase, RetrievalProfile profile) {
        return new RetrievalBundle(
                evaluationCase.query(), profile, evaluationCase.projectId(),
                evaluationCase.documentId(), evaluationCase.version(),
                List.of(), List.of(), List.of());
    }

    private long stageLatency(List<RagStageDiagnostic> diagnostics, boolean codeStage) {
        return diagnostics.stream()
                .filter(diagnostic -> codeStage
                        ? diagnostic.stage().startsWith("code.")
                        : !diagnostic.stage().startsWith("code."))
                .mapToLong(RagStageDiagnostic::durationMs)
                .sum();
    }

    private long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private String safeError(RuntimeException exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    record EvaluationRetrieval(
            RetrievalBundle bundle,
            long documentLatencyMs,
            long codeLatencyMs,
            long totalLatencyMs,
            String documentError,
            String codeError,
            List<RagWarning> warnings,
            List<RagStageDiagnostic> diagnostics,
            RetrievalEvaluationMatcher.EvaluationTrace trace
    ) {
    }

    record RequirementTrace(List<ChunkRecord> candidates, List<ChunkRecord> reranked) {
        RequirementTrace {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            reranked = reranked == null ? List.of() : List.copyOf(reranked);
        }

        static RequirementTrace empty() {
            return new RequirementTrace(List.of(), List.of());
        }
    }

    static final class TracingRequirementReranker implements RequirementReranker {
        private final RequirementReranker delegate;
        private final ConcurrentHashMap<String, RequirementTrace> traces = new ConcurrentHashMap<>();

        TracingRequirementReranker(RequirementReranker delegate) {
            this.delegate = delegate;
        }

        @Override
        public RagOutcome<List<ChunkRecord>> rerank(String query, String documentId, String version,
                                                    List<ChunkRecord> candidates, int limit) {
            List<ChunkRecord> safeCandidates = candidates == null ? List.of() : List.copyOf(candidates);
            try {
                RagOutcome<List<ChunkRecord>> outcome = delegate.rerank(
                        query, documentId, version, safeCandidates, limit);
                List<ChunkRecord> reranked = outcome.data() == null ? List.of() : outcome.data();
                traces.put(query, new RequirementTrace(safeCandidates, reranked));
                return outcome;
            } catch (RuntimeException exception) {
                traces.put(query, new RequirementTrace(safeCandidates, List.of()));
                throw exception;
            }
        }

        RequirementTrace take(String query) {
            return traces.remove(query);
        }

        void clear() {
            traces.clear();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EvaluationRerankerConfiguration {
        @Bean
        @Primary
        TracingRequirementReranker evaluationRequirementReranker(
                @Qualifier("defaultRequirementReranker") DefaultRequirementReranker productionReranker) {
            RequirementReranker delegate = SETTINGS.mode() == RetrievalEvaluationSettings.EvaluationMode.BASELINE_0_7
                    ? RequirementReranker.passthrough()
                    : productionReranker;
            return new TracingRequirementReranker(delegate);
        }
    }
}
