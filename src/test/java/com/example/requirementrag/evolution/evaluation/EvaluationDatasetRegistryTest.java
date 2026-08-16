package com.example.requirementrag.evolution.evaluation;

import com.example.requirementrag.config.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationDatasetRegistryTest {

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
    void rejectsOverwritingExistingVersion() {
        EvaluationDatasetRegistry registry = new EvaluationDatasetRegistry(
                new ObjectMapper().findAndRegisterModules(), properties());
        EvaluationDataset dataset = new EvaluationDataset("ds-1", List.of(), Instant.now(), null);
        registry.publish(dataset);

        assertThatThrownBy(() -> registry.publish(dataset))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }
}
