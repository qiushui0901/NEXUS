package com.example.requirementrag.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagPropertiesBgeTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BgePropertiesConfiguration.class);

    @Test
    void appliesBgeTimeoutDefaultsForLegacyConstructionAndPropertyBinding() {
        RagProperties.Bge legacy = new RagProperties.Bge("http://localhost:8081", "/rerank", "");
        assertThat(legacy.connectTimeoutMs()).isEqualTo(2_000);
        assertThat(legacy.readTimeoutMs()).isEqualTo(10_000);

        contextRunner.withPropertyValues(
                        "app.rag.bge.base-url=http://localhost:8081",
                        "app.rag.bge.path=/rerank",
                        "app.rag.bge.api-key=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RagProperties.Bge bound = context.getBean(RagProperties.class).bge();
                    assertThat(bound.connectTimeoutMs()).isEqualTo(2_000);
                    assertThat(bound.readTimeoutMs()).isEqualTo(10_000);
                });
    }

    @Test
    void bindsExplicitBgeTimeouts() {
        contextRunner.withPropertyValues(
                        "app.rag.bge.base-url=http://localhost:8081",
                        "app.rag.bge.path=/rerank",
                        "app.rag.bge.connect-timeout-ms=1500",
                        "app.rag.bge.read-timeout-ms=9000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RagProperties.Bge bound = context.getBean(RagProperties.class).bge();
                    assertThat(bound.connectTimeoutMs()).isEqualTo(1_500);
                    assertThat(bound.readTimeoutMs()).isEqualTo(9_000);
                });
    }

    @Test
    void rejectsNegativeOrUnboundedBgeTimeouts() {
        assertThatThrownBy(() -> new RagProperties.Bge("http://localhost", "/rerank", "", -1, 10_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectTimeoutMs");
        assertThatThrownBy(() -> new RagProperties.Bge("http://localhost", "/rerank", "", 2_000, 120_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("readTimeoutMs");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RagProperties.class)
    static class BgePropertiesConfiguration {
    }
}
