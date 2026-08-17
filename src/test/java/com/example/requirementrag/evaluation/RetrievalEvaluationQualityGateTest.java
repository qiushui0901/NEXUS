package com.example.requirementrag.evaluation;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.RagWarning;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalEvaluationQualityGateTest {

    private static final String DATASET = "evaluation/retrieval-eval-enterprise-v2.jsonl";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesVersionedNestedThresholds() throws Exception {
        RetrievalEvaluationQualityGate.Thresholds thresholds = RetrievalEvaluationQualityGate.parse(
                mapper.readTree("""
                        {
                          "schemaVersion": 1,
                          "dataset": "evaluation/retrieval-eval-enterprise-v2.jsonl",
                          "minimum": {
                            "documentRecallAt10": 0.85,
                            "codeRecallAt10": 0.75,
                            "mrrAt10": 0.70,
                            "ndcgAt10": 0.75,
                            "noResultAccuracy": 0.90
                          },
                          "maximum": {
                            "degradationRate": 0.05,
                            "p95LatencyMs": 5000
                          }
                        }
                        """), "nested-test");

        assertEquals(1, thresholds.schemaVersion());
        assertEquals(DATASET, thresholds.dataset());
        assertEquals(0.75, thresholds.ndcgAt10());
        assertEquals(0.05, thresholds.degradationRate());
        assertEquals(5000L, thresholds.p95LatencyMs());
    }

    @Test
    void parsesLegacyFlatBaselineWithoutInventingNewThresholds() throws Exception {
        RetrievalEvaluationQualityGate.Thresholds thresholds = RetrievalEvaluationQualityGate.parse(
                mapper.readTree("""
                        {
                          "documentRecallAt10": 0.80,
                          "codeRecallAt10": 0.75,
                          "mrrAt10": 0.65,
                          "p95LatencyMs": 5000
                        }
                        """), "legacy-test");

        assertEquals(0, thresholds.schemaVersion());
        assertEquals(0.80, thresholds.documentRecallAt10());
        assertEquals(0.75, thresholds.codeRecallAt10());
        assertEquals(0.65, thresholds.mrrAt10());
        assertEquals(5000L, thresholds.p95LatencyMs());
        assertEquals(null, thresholds.ndcgAt10());
        assertEquals(null, thresholds.degradationRate());
    }

    @Test
    void passesAHealthyReport() {
        RetrievalEvaluationQualityGate.GateResult result =
                RetrievalEvaluationQualityGate.evaluate(healthyReport(), thresholds(DATASET));

        assertTrue(result.passed());
        assertTrue(result.failures().isEmpty());
        result.requirePassed();
    }

    @Test
    void reportsMultipleQualityFailuresTogether() {
        RetrievalEvaluationCase document = documentCase();
        RetrievalEvaluationCase code = codeCase();
        RetrievalEvaluationCase noAnswer = noAnswerCase();
        RetrievalEvaluationReport report = RetrievalEvaluationReport.create(
                DATASET, "0.8.6-enterprise", 0, 1, List.of(
                        RetrievalEvaluationMatcher.evaluate(document, List.of(), List.of(), 10, 0, 10,
                                null, null, 1,
                                List.of(new RagWarning("retrieval", "QUALITY_DEGRADED", "degraded", 1)),
                                List.of()),
                        RetrievalEvaluationMatcher.evaluate(code, List.of(), List.of(), 0, 10, 10),
                        RetrievalEvaluationMatcher.evaluate(
                                noAnswer, List.of(chunk("unexpected.md")), List.of(), 10, 0, 10)));

        RetrievalEvaluationQualityGate.GateResult result =
                RetrievalEvaluationQualityGate.evaluate(report, thresholds(DATASET));

        assertFalse(result.passed());
        assertTrue(result.failures().stream().anyMatch(value -> value.contains("Document Recall@10")));
        assertTrue(result.failures().stream().anyMatch(value -> value.contains("Code Recall@10")));
        assertTrue(result.failures().stream().anyMatch(value -> value.contains("MRR@10")));
        assertTrue(result.failures().stream().anyMatch(value -> value.contains("nDCG@10")));
        assertTrue(result.failures().stream().anyMatch(value -> value.contains("No-result accuracy")));
        assertTrue(result.failures().stream().anyMatch(value -> value.contains("Degradation rate")));
        assertTrue(result.failures().stream().anyMatch(value -> value.contains("P95 latency")));
        assertThrows(AssertionError.class, result::requirePassed);
    }

    @Test
    void infrastructureFailuresBlockEvenWhenQualityMetricsPass() {
        RetrievalEvaluationReport healthy = healthyReport();
        RetrievalEvaluationMatcher.CaseResult unavailable = RetrievalEvaluationMatcher.evaluate(
                documentCase(), List.of(chunk("gold.md")), List.of(), 1, 0, 1,
                null, null, 1,
                List.of(new RagWarning("retrieval", "QDRANT_UNAVAILABLE", "unavailable", 1)),
                List.of());
        RetrievalEvaluationReport report = RetrievalEvaluationReport.create(
                DATASET, "0.8.6-enterprise", 0, 1,
                List.of(unavailable, healthy.cases().get(1), healthy.cases().get(2)));

        RetrievalEvaluationQualityGate.GateResult result =
                RetrievalEvaluationQualityGate.evaluate(report, thresholds(DATASET));

        assertFalse(result.passed());
        assertTrue(result.failures().stream().anyMatch(value -> value.contains("基础设施失败")));
    }

    @Test
    void datasetMismatchBlocksBeforeScoresCanBeTrusted() {
        RetrievalEvaluationQualityGate.GateResult result =
                RetrievalEvaluationQualityGate.evaluate(healthyReport(), thresholds("evaluation/other.jsonl"));

        assertFalse(result.passed());
        assertTrue(result.failures().stream().anyMatch(value -> value.contains("数据集不匹配")));
    }

    private RetrievalEvaluationReport healthyReport() {
        return RetrievalEvaluationReport.create(
                DATASET, "0.8.6-enterprise", 0, 1, List.of(
                        RetrievalEvaluationMatcher.evaluate(
                                documentCase(), List.of(chunk("gold.md")), List.of(), 1, 0, 1),
                        RetrievalEvaluationMatcher.evaluate(
                                codeCase(), List.of(), List.of(code("src/Service.java", "run")), 0, 1, 1),
                        RetrievalEvaluationMatcher.evaluate(
                                noAnswerCase(), List.of(), List.of(), 1, 1, 2)));
    }

    private RetrievalEvaluationQualityGate.Thresholds thresholds(String dataset) {
        return new RetrievalEvaluationQualityGate.Thresholds(
                1, dataset, 0.85, 0.75, 0.70, 0.75, 0.90, 0.05, 5L);
    }

    private RetrievalEvaluationCase documentCase() {
        return new RetrievalEvaluationCase(
                "document-case", "document query",
                RetrievalEvaluationCase.RetrievalProfile.REQUIREMENT_REVIEW,
                "project", "doc", "1", RetrievalEvaluationCase.ExpectedOutcome.HIT,
                List.of(new RetrievalEvaluationCase.GoldDocument("gold.md", null, List.of())),
                List.of(), List.of(), "test");
    }

    private RetrievalEvaluationCase codeCase() {
        return new RetrievalEvaluationCase(
                "code-case", "code query",
                RetrievalEvaluationCase.RetrievalProfile.DEVELOPMENT_PLAN,
                "project", "doc", "1", RetrievalEvaluationCase.ExpectedOutcome.HIT,
                List.of(),
                List.of(new RetrievalEvaluationCase.GoldCode("project", "src/Service.java", "run")),
                List.of(), "test");
    }

    private RetrievalEvaluationCase noAnswerCase() {
        return new RetrievalEvaluationCase(
                "no-answer-case", "no answer query",
                RetrievalEvaluationCase.RetrievalProfile.REQUIREMENT_REVIEW,
                "project", "missing", "missing", RetrievalEvaluationCase.ExpectedOutcome.NO_RESULTS,
                List.of(), List.of(), List.of(), "test");
    }

    private ChunkRecord chunk(String filename) {
        return new ChunkRecord("id", "doc", "1", filename, "parent", "", "",
                "hash", 0, 0);
    }

    private CodeChunk code(String filePath, String symbolName) {
        return new CodeChunk("id", "project", "commit", filePath, "method", symbolName,
                1, 2, "text", "hash");
    }
}
