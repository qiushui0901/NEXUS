package com.example.requirementrag.retrieval.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalCircuitBreakerTest {
    @Test
    void opensAfterThresholdAndSuccessResetsFailures() {
        RetrievalCircuitBreaker breaker = new RetrievalCircuitBreaker(2, Duration.ofSeconds(10));

        breaker.failure("code");
        assertThat(breaker.allow("code")).isTrue();
        breaker.success("code");
        breaker.failure("code");
        assertThat(breaker.allow("code")).isTrue();
        breaker.failure("code");

        assertThat(breaker.allow("code")).isFalse();
        assertThat(breaker.allow("requirements")).isTrue();
    }
}
