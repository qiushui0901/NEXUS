package com.example.requirementrag.requirement.graph;

import io.micrometer.core.instrument.MeterRegistry;

/** Safe metrics facade for graph build/search; no source text or model payloads are recorded. */
public final class RequirementGraphObservability {
    private final MeterRegistry registry;

    public RequirementGraphObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    public void count(String name, String projectId, String status) {
        if (registry == null) return;
        registry.counter(name, "project", safe(projectId), "status", safe(status)).increment();
    }

    public void value(String name, String projectId, String status, double value) {
        if (registry == null) return;
        registry.summary(name, "project", safe(projectId), "status", safe(status)).record(Math.max(0, value));
    }

    public void timer(String name, String projectId, String status, long durationMs) {
        if (registry == null) return;
        registry.timer(name, "project", safe(projectId), "status", safe(status))
                .record(Math.max(0, durationMs), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }
}
