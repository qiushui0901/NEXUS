package com.example.requirementrag.evolution.evaluation;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.evolution.policy.PolicyStatus;
import com.example.requirementrag.evolution.policy.RetrievalPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvolutionExperimentRunnerTest {

    @TempDir
    Path tempDir;

    private RagProperties properties() {
        return new RagProperties(
                new RagProperties.Qdrant("http://localhost:6333", "requirement_chunks"),
                new RagProperties.Bge("http://localhost:8081", "/rerank", null),
                new RagProperties.Llm("claude-sonnet-5", "claude-sonnet-4.6", null, null, null),
                new RagProperties.Retrieval(50, 50, 40, 20, 10, true, 5_000, 6, 3, 30_000,
                        120, 1000, 900, 10000, null, null, null, null, null, 0.0),
                new RagProperties.Knowledge(false, null, null, "requirements", "5.1", null, null, 800),
                new RagProperties.Review(12, 15, 12, 3),
                new RagProperties.Code("demo", "/tmp", "code_demo", List.of(), List.of(), 1_000_000),
                List.of(),
                new RagProperties.Evolution(true, true, 1.0, 1.0, 10, true, 30,
                        tempDir.resolve("experiences").toString(), tempDir.resolve("candidates").toString(),
                        tempDir.resolve("datasets").toString(), tempDir.resolve("policies").toString()));
    }

    @Test
    void runsEachCaseForRequestedRepetitionsAndReportsDegradedRate() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RetrievalPolicy baseline = new RetrievalPolicy("base", "1", PolicyStatus.DRAFT,
                Map.of(), Map.of(), Map.of(), Map.of(), null, null, "c", Instant.now());
        RetrievalPolicy candidate = new RetrievalPolicy("cand", "1", PolicyStatus.DRAFT,
                Map.of(), Map.of(), Map.of(), Map.of(), null, null, "c", Instant.now());
        RetrievalPolicyExecutor executor = (evalCase, policy, seed, repetition) -> {
            if (policy == baseline) {
                return new RetrievalPolicyExecutor.ExecutionResult(List.of("b"), "SUCCESS");
            }
            return new RetrievalPolicyExecutor.ExecutionResult(List.of("c"), "DEGRADED");
        };
        EvolutionExperimentRunner runner = new EvolutionExperimentRunner(executor, mapper, properties());
        EvaluationDataset dataset = new EvaluationDataset("ds-1",
                List.of(new EvaluationCase("c1", "query", null, null, List.of("gold"))),
                Instant.now(), null);

        ExperimentReport report = runner.run(dataset, baseline, candidate, "idx", "model", 42, 3);

        assertThat(report.manifest().repetitions()).isEqualTo(3);
        assertThat(report.cases()).hasSize(3);
        assertThat(report.candidate().degradedRate()).isEqualTo(1.0);
        assertThat(report.baseline().degradedRate()).isZero();
    }
}
