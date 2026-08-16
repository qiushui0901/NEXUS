package com.example.requirementrag.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagConfigValidatorTest {

    private RagProperties valid() {
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
                RagProperties.Evolution.disabled());
    }

    @Test
    void acceptsValidConfiguration() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.ai.openai.base-url", "http://ai-gateway.momo.com");
        env.setProperty("spring.ai.openai.embedding.options.model", "text-embedding-v4");

        new RagConfigValidator(valid(), env).validate();
    }

    @Test
    void rejectsInvalidTopKRelationship() {
        RagProperties invalid = new RagProperties(
                valid().qdrant(), valid().bge(), valid().llm(),
                new RagProperties.Retrieval(40, 50, 50, 20, 10, true, 5_000, 6, 3, 30_000,
                        120, 1000, 900, 10000, null, null, null, null, null, 0.0),
                valid().knowledge(), valid().review(), valid().code(), List.of(),
                valid().evolution());
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.ai.openai.embedding.options.model", "text-embedding-v4");

        assertThatThrownBy(() -> new RagConfigValidator(invalid, env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dense-top-k");
    }

    @Test
    void rejectsBlankQdrantCollection() {
        RagProperties invalid = new RagProperties(
                new RagProperties.Qdrant("http://localhost:6333", ""),
                valid().bge(), valid().llm(), valid().retrieval(),
                valid().knowledge(), valid().review(), valid().code(), List.of(),
                valid().evolution());
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.ai.openai.embedding.options.model", "text-embedding-v4");

        assertThatThrownBy(() -> new RagConfigValidator(invalid, env).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collection");
    }
}
