package com.example.requirementrag.evaluation;

import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagStageDiagnostic;
import com.example.requirementrag.model.RagWarning;
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
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

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

    @Autowired
    private RetrievalPipeline retrievalPipeline;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void generatesFrozenPipelineEvaluation() throws Exception {
        List<RetrievalEvaluationCase> dataset =
                RetrievalEvaluationDataset.loadResource(SETTINGS.datasetResource());

        for (int warmup = 0; warmup < SETTINGS.warmupRuns(); warmup++) {
            for (RetrievalEvaluationCase evaluationCase : dataset) {
                retrieve(evaluationCase);
            }
        }

        List<RetrievalEvaluationMatcher.CaseResult> results = new ArrayList<>();
        for (int repetition = 1; repetition <= SETTINGS.repetitions(); repetition++) {
            for (RetrievalEvaluationCase evaluationCase : dataset) {
                EvaluationRetrieval retrieval = retrieve(evaluationCase);
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
                        retrieval.diagnostics()));
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
        if (SETTINGS.mode() == RetrievalEvaluationSettings.EvaluationMode.RERANK_0_8
                && report.summary().bgeCalls() == 0) {
            throw new AssertionError("0.8 rerank evaluation did not exercise the BGE reranker");
        }
    }

    private EvaluationRetrieval retrieve(RetrievalEvaluationCase evaluationCase) {
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
            return new EvaluationRetrieval(
                    bundle,
                    stageLatency(outcome.stageDiagnostics(), false),
                    profile.usesCodeEvidence() ? stageLatency(outcome.stageDiagnostics(), true) : 0,
                    totalLatency,
                    null,
                    null,
                    outcome.warnings(),
                    outcome.stageDiagnostics());
        } catch (RuntimeException exception) {
            long latency = elapsedMillis(started);
            String error = safeError(exception);
            return new EvaluationRetrieval(
                    emptyBundle(evaluationCase, profile),
                    latency,
                    profile.usesCodeEvidence() ? latency : 0,
                    latency,
                    error,
                    profile.usesCodeEvidence() ? error : null,
                    List.of(),
                    List.of());
        }
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
            List<RagStageDiagnostic> diagnostics
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EvaluationRerankerConfiguration {
        @Bean
        @Primary
        RequirementReranker evaluationRequirementReranker(
                @Qualifier("defaultRequirementReranker") DefaultRequirementReranker productionReranker) {
            return SETTINGS.mode() == RetrievalEvaluationSettings.EvaluationMode.BASELINE_0_7
                    ? RequirementReranker.passthrough()
                    : productionReranker;
        }
    }
}
