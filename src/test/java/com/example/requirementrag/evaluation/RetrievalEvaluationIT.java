package com.example.requirementrag.evaluation;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@SpringBootTest(properties = {
        "logging.structured.format.console=",
        "management.tracing.sampling.probability=0",
        "app.rag.knowledge.bootstrap-enabled=false",
        "app.rag.auth.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "RUN_RETRIEVAL_EVAL", matches = "(?i)true")
class RetrievalEvaluationIT {

    private static final Path REPORT_DIRECTORY = Path.of("target", "retrieval-evaluation");
    private static final String CODE_SEARCH_FALLBACK_URL = System.getenv("RETRIEVAL_EVAL_CODE_SEARCH_URL");

    private final AtomicBoolean codeFallbackUsed = new AtomicBoolean();

    @Autowired
    private QdrantHybridStore documentStore;

    @Autowired
    private CodeKnowledgeService codeKnowledgeService;

    @Autowired
    private ProjectRegistry projectRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void generatesCurrentPipelineBaseline() throws Exception {
        List<RetrievalEvaluationMatcher.CaseResult> results = new ArrayList<>();
        for (RetrievalEvaluationCase evaluationCase : RetrievalEvaluationDataset.loadDefault()) {
            long totalStarted = System.nanoTime();
            TimedResult<ChunkRecord> documents = retrieveDocuments(evaluationCase);
            TimedResult<CodeChunk> code = retrieveCode(evaluationCase);
            long totalLatencyMs = elapsedMillis(totalStarted);
            results.add(RetrievalEvaluationMatcher.evaluate(
                    evaluationCase, documents.values(), code.values(),
                    documents.latencyMs(), code.latencyMs(), totalLatencyMs,
                    documents.error(), code.error()));
        }

        RetrievalEvaluationReport report = RetrievalEvaluationReport.create(
                RetrievalEvaluationDataset.DEFAULT_RESOURCE, results);
        report.write(REPORT_DIRECTORY, objectMapper);

        if (codeFallbackUsed.get()) {
            System.out.println("Code retrieval used explicit RETRIEVAL_EVAL_CODE_SEARCH_URL fallback.");
        }
        System.out.printf("Retrieval baseline written to %s (failed cases: %d/%d, infrastructure failures: %d)%n",
                REPORT_DIRECTORY.toAbsolutePath(), report.summary().failedCases(), report.summary().totalCases(),
                report.summary().infrastructureFailureCases());
        if (report.summary().infrastructureFailureCases() > 0) {
            throw new AssertionError("Retrieval evaluation had " + report.summary().infrastructureFailureCases()
                    + " infrastructure failure case(s); inspect target/retrieval-evaluation/report.md");
        }
    }

    private TimedResult<ChunkRecord> retrieveDocuments(RetrievalEvaluationCase evaluationCase) {
        if (!shouldRetrieveDocuments(evaluationCase)) {
            return new TimedResult<>(List.of(), 0, null);
        }
        long started = System.nanoTime();
        String collection = projectRegistry.resolveRequirementCollection(evaluationCase.projectId());
        try {
            List<ChunkRecord> values = documentStore.hybridSearch(
                    collection, evaluationCase.query(), evaluationCase.documentId(), evaluationCase.version());
            return new TimedResult<>(values, elapsedMillis(started), null);
        } catch (RuntimeException exception) {
            return new TimedResult<>(List.of(), elapsedMillis(started), safeError(exception));
        }
    }

    private TimedResult<CodeChunk> retrieveCode(RetrievalEvaluationCase evaluationCase) {
        if (!shouldRetrieveCode(evaluationCase)) {
            return new TimedResult<>(List.of(), 0, null);
        }
        long started = System.nanoTime();
        try {
            List<CodeChunk> values = codeKnowledgeService.search(
                    evaluationCase.query(), evaluationCase.projectId(), RetrievalEvaluationMatcher.DEFAULT_CUTOFF);
            return new TimedResult<>(values, elapsedMillis(started), null);
        } catch (RuntimeException exception) {
            if (CODE_SEARCH_FALLBACK_URL == null || CODE_SEARCH_FALLBACK_URL.isBlank()) {
                return new TimedResult<>(List.of(), elapsedMillis(started), safeError(exception));
            }
            try {
                List<CodeChunk> values = retrieveCodeOverHttp(evaluationCase);
                codeFallbackUsed.set(true);
                return new TimedResult<>(values, elapsedMillis(started), null);
            } catch (RuntimeException fallbackException) {
                return new TimedResult<>(List.of(), elapsedMillis(started),
                        safeError(exception) + "; fallback=" + safeError(fallbackException));
            }
        }
    }

    private boolean shouldRetrieveDocuments(RetrievalEvaluationCase evaluationCase) {
        if (!evaluationCase.goldDocuments().isEmpty()) {
            return true;
        }
        return evaluationCase.expectedOutcome() == RetrievalEvaluationCase.ExpectedOutcome.NO_RESULTS
                && evaluationCase.profile() != RetrievalEvaluationCase.RetrievalProfile.CODE_SEARCH;
    }

    private boolean shouldRetrieveCode(RetrievalEvaluationCase evaluationCase) {
        if (!evaluationCase.goldCode().isEmpty()) {
            return true;
        }
        return evaluationCase.expectedOutcome() == RetrievalEvaluationCase.ExpectedOutcome.NO_RESULTS
                && evaluationCase.profile() != RetrievalEvaluationCase.RetrievalProfile.REQUIREMENT_REVIEW;
    }

    private List<CodeChunk> retrieveCodeOverHttp(RetrievalEvaluationCase evaluationCase) {
        List<Map<String, Object>> response = RestClient.create(CODE_SEARCH_FALLBACK_URL)
                .post()
                .uri("/api/code/search")
                .body(Map.of(
                        "query", evaluationCase.query(),
                        "projectId", evaluationCase.projectId(),
                        "limit", RetrievalEvaluationMatcher.DEFAULT_CUTOFF))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        if (response == null) {
            return List.of();
        }
        return response.stream().map(this::toCodeChunk).toList();
    }

    private CodeChunk toCodeChunk(Map<String, Object> value) {
        return new CodeChunk(
                string(value, "id"), string(value, "projectId"), string(value, "commitSha"),
                string(value, "filePath"), string(value, "symbolType"), string(value, "symbolName"),
                integer(value, "startLine"), integer(value, "endLine"), string(value, "text"),
                string(value, "contentHash"));
    }

    private String string(Map<String, Object> value, String key) {
        return String.valueOf(value.getOrDefault(key, ""));
    }

    private int integer(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        return raw instanceof Number number ? number.intValue() : 0;
    }

    private static String safeError(RuntimeException exception) {
        return exception.getClass().getSimpleName();
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private record TimedResult<T>(List<T> values, long latencyMs, String error) {
    }
}
