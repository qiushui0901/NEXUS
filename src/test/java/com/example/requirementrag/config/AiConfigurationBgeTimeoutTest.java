package com.example.requirementrag.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigurationBgeTimeoutTest {

    private final AiConfiguration configuration = new AiConfiguration();

    @Test
    void appliesConfiguredTimeoutsOnlyToBgeRequestFactory() {
        RagProperties.Bge bge = new RagProperties.Bge(
                "http://127.0.0.1:8081", "/rerank", "", 1_500, 9_000);

        SimpleClientHttpRequestFactory requestFactory = configuration.bgeRequestFactory(bge);

        assertTimeouts(requestFactory, 1_500, 9_000);
    }

    @Test
    void keepsQdrantTimeoutBudgetIndependentFromBgeBudget() {
        RagProperties.Bge bge = new RagProperties.Bge(
                "http://127.0.0.1:8081", "/rerank", "", 2_000, 10_000);

        assertTimeouts(configuration.bgeRequestFactory(bge), 2_000, 10_000);
        assertTimeouts(configuration.qdrantRequestFactory(), 2_000, 5_000);
    }

    private void assertTimeouts(SimpleClientHttpRequestFactory requestFactory,
                                int expectedConnectTimeoutMs, int expectedReadTimeoutMs) {
        assertThat(ReflectionTestUtils.getField(requestFactory, "connectTimeout"))
                .isEqualTo(expectedConnectTimeoutMs);
        assertThat(ReflectionTestUtils.getField(requestFactory, "readTimeout"))
                .isEqualTo(expectedReadTimeoutMs);
    }
}
