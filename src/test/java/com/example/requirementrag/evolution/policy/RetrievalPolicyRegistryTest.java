package com.example.requirementrag.evolution.policy;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.evolution.evaluation.EvaluationCase;
import com.example.requirementrag.evolution.evaluation.EvaluationDataset;
import com.example.requirementrag.evolution.evaluation.EvolutionExperimentRunner;
import com.example.requirementrag.evolution.evaluation.ExperimentReport;
import com.example.requirementrag.evolution.evaluation.RetrievalPolicyExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalPolicyRegistryTest {

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
    void activatesOnlyApprovedPolicy() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RetrievalPolicyRegistry registry = new RetrievalPolicyRegistry(mapper, properties());
        RetrievalPolicyExecutor executor = (evalCase, policy, seed, repetition) ->
                new RetrievalPolicyExecutor.ExecutionResult(List.of(), "SUCCESS");
        EvolutionExperimentRunner runner = new EvolutionExperimentRunner(executor, mapper, properties());
        PolicyLifecycleService lifecycle = new PolicyLifecycleService(registry, new PolicyPromotionGate(), runner);

        lifecycle.createDraft("policy-a", "1", Map.of("selector.code-intent-strategy", "code"),
                Map.of("weights.dense", 1.0), Map.of("orchestrator.max-hops", 2),
                Map.of("rerank.bge-enabled", true), null);
        lifecycle.createDraft("baseline", "1", Map.of(), Map.of(), Map.of(), Map.of(), null);
        lifecycle.submitEvaluating("policy-a", "1");

        EvaluationDataset dataset = new EvaluationDataset("ds-1",
                List.of(new EvaluationCase("c1", "query", null, null, List.of())),
                Instant.now(), null);
        RetrievalPolicy baseline = registry.find("baseline", "1");
        RetrievalPolicy candidate = registry.find("policy-a", "1");
        ExperimentReport report = runner.run(dataset, baseline, candidate, "idx", "model", 1, 1);
        lifecycle.approve("policy-a", "1", report.manifest().experimentId());
        lifecycle.activate("policy-a", "1");

        RetrievalPolicy active = registry.active();
        assertThat(active).isNotNull();
        assertThat(active.status()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    void rejectsApprovalWithoutPassingGate() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RetrievalPolicyRegistry registry = new RetrievalPolicyRegistry(mapper, properties());
        PolicyLifecycleService lifecycle = new PolicyLifecycleService(registry);

        lifecycle.createDraft("policy-a", "1", Map.of("selector.code-intent-strategy", "code"),
                Map.of(), Map.of(), Map.of(), null);
        lifecycle.submitEvaluating("policy-a", "1");

        assertThatThrownBy(() -> lifecycle.approve("policy-a", "1", "missing-exp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("批准策略必须提供实验报告 ID");
    }

    @Test
    void rejectsUnsupportedParameter() {
        RetrievalPolicyRegistry registry = new RetrievalPolicyRegistry(new ObjectMapper().findAndRegisterModules(), properties());
        PolicyLifecycleService lifecycle = new PolicyLifecycleService(registry);

        assertThatThrownBy(() -> lifecycle.createDraft("bad", "1",
                Map.of("selector.unknown", "code"), Map.of(), Map.of(), Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported selectorRules key");
    }

    @Test
    void rejectsOverwritingVersionWithDifferentContent() {
        RetrievalPolicyRegistry registry = new RetrievalPolicyRegistry(new ObjectMapper().findAndRegisterModules(), properties());
        PolicyLifecycleService lifecycle = new PolicyLifecycleService(registry);

        lifecycle.createDraft("policy-a", "1", Map.of("selector.code-intent-strategy", "code"),
                Map.of(), Map.of(), Map.of(), null);

        assertThatThrownBy(() -> lifecycle.createDraft("policy-a", "1",
                Map.of("selector.code-intent-strategy", "requirements"),
                Map.of(), Map.of(), Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不可覆盖");
    }
}
