package com.example.requirementrag.evolution.policy;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.retrieval.agentic.RetrievalStrategy;
import com.example.requirementrag.retrieval.agentic.StrategyResult;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyDrivenRetrievalStrategySelectorTest {

    @TempDir
    Path tempDir;

    private RagProperties properties(boolean enabled) {
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
                new RagProperties.Evolution(enabled, false, 1.0, 1.0, 10, true, 30,
                        tempDir.resolve("experiences").toString(), tempDir.resolve("candidates").toString(),
                        tempDir.resolve("datasets").toString(), tempDir.resolve("policies").toString()));
    }

    private RetrievalStrategy strategy(String name) {
        return new RetrievalStrategy() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public StrategyResult execute(RetrievalRequest request) {
                return null;
            }
        };
    }

    @Test
    void ignoresActivePolicyWhenEvolutionDisabled() {
        RagProperties disabled = properties(false);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RetrievalPolicyRegistry registry = new RetrievalPolicyRegistry(mapper, disabled);
        registry.save(new RetrievalPolicy("p", "1", PolicyStatus.APPROVED,
                Map.of("selector.code-intent-strategy", "requirements"),
                Map.of(), Map.of(), Map.of(), null, null, "checksum", Instant.now()));
        registry.activate("p", "1");

        PolicyDrivenRetrievalStrategySelector selector =
                new PolicyDrivenRetrievalStrategySelector(registry, disabled);
        RetrievalRequest request = new RetrievalRequest("如何实现英雄升级", RetrievalProfile.DEVELOPMENT_PLAN,
                "demo", null, "5.1", 10);
        Optional<RetrievalStrategy> selected = selector.select(
                List.of(strategy("code"), strategy("requirements")), request);

        assertThat(selected).isPresent();
        assertThat(selected.get().name()).isEqualTo("code");
    }

    @Test
    void usesActivePolicyWhenEvolutionEnabled() {
        RagProperties enabled = properties(true);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RetrievalPolicyRegistry registry = new RetrievalPolicyRegistry(mapper, enabled);
        registry.save(new RetrievalPolicy("p", "1", PolicyStatus.APPROVED,
                Map.of("selector.code-intent-strategy", "requirements"),
                Map.of(), Map.of(), Map.of(), null, null, "checksum", Instant.now()));
        registry.activate("p", "1");

        PolicyDrivenRetrievalStrategySelector selector =
                new PolicyDrivenRetrievalStrategySelector(registry, enabled);
        RetrievalRequest request = new RetrievalRequest("如何实现英雄升级", RetrievalProfile.DEVELOPMENT_PLAN,
                "demo", null, "5.1", 10);
        Optional<RetrievalStrategy> selected = selector.select(
                List.of(strategy("code"), strategy("requirements")), request);

        assertThat(selected).isPresent();
        assertThat(selected.get().name()).isEqualTo("requirements");
    }
}
