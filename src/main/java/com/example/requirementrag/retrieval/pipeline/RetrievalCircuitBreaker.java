package com.example.requirementrag.retrieval.pipeline;

import com.example.requirementrag.config.RagProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/** Lightweight per-stage circuit breaker that prevents repeated calls during dependency outages. */
@Component
public class RetrievalCircuitBreaker {
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private final int failureThreshold;
    private final long openMillis;

    @Autowired
    public RetrievalCircuitBreaker(RagProperties properties) {
        this(properties.retrieval().resolvedCircuitBreakerFailureThreshold(),
                Duration.ofMillis(properties.retrieval().resolvedCircuitBreakerOpenMs()));
    }

    RetrievalCircuitBreaker(int failureThreshold, Duration openDuration) {
        this.failureThreshold = Math.max(0, failureThreshold);
        this.openMillis = Math.max(0, openDuration == null ? 0 : openDuration.toMillis());
    }

    public boolean allow(String stage) {
        if (failureThreshold == 0 || openMillis == 0) return true;
        State state = states.get(stage);
        if (state == null) return true;
        if (state.openUntilMillis > 0 && state.openUntilMillis <= System.currentTimeMillis()) {
            states.remove(stage, state);
            return true;
        }
        return state.openUntilMillis == 0;
    }

    public void success(String stage) {
        states.remove(stage);
    }

    public void failure(String stage) {
        if (failureThreshold == 0 || openMillis == 0) return;
        states.compute(stage, (ignored, previous) -> {
            int failures = previous == null ? 1 : previous.failures + 1;
            long openUntil = failures >= failureThreshold ? System.currentTimeMillis() + openMillis : 0;
            return new State(failures, openUntil);
        });
    }

    private record State(int failures, long openUntilMillis) {
    }
}
