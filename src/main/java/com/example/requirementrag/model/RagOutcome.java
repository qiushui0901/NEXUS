package com.example.requirementrag.model;

import java.util.List;

/** 带统一状态、警告和阶段诊断的内部 RAG 结果。 */
public record RagOutcome<T>(
        RagOutcomeStatus status,
        T data,
        List<RagWarning> warnings,
        List<RagStageDiagnostic> stageDiagnostics
) {
    public RagOutcome {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        stageDiagnostics = stageDiagnostics == null ? List.of() : List.copyOf(stageDiagnostics);
    }

    public static <T> RagOutcome<T> of(RagOutcomeStatus status, T data, String stage,
                                       long durationMs, long itemCount) {
        return new RagOutcome<>(status, data, List.of(),
                List.of(new RagStageDiagnostic(stage, status, durationMs, itemCount)));
    }

    public static <T> RagOutcome<T> degraded(T data, String stage, String code,
                                             String message, long durationMs, long itemCount) {
        return new RagOutcome<>(RagOutcomeStatus.DEGRADED, data,
                List.of(new RagWarning(stage, code, message, durationMs)),
                List.of(new RagStageDiagnostic(stage, RagOutcomeStatus.DEGRADED, durationMs, itemCount)));
    }

    public static <T> RagOutcome<T> failed(T data, String stage, String code,
                                           String message, long durationMs) {
        return new RagOutcome<>(RagOutcomeStatus.FAILED, data,
                List.of(new RagWarning(stage, code, message, durationMs)),
                List.of(new RagStageDiagnostic(stage, RagOutcomeStatus.FAILED, durationMs, 0)));
    }
}
