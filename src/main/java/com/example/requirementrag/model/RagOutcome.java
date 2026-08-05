package com.example.requirementrag.model;

import java.util.List;

/** 带统一状态、警告和阶段诊断的内部 RAG 结果。 */
public record RagOutcome<T>(
        RagOutcomeStatus status,
        T data,
        List<RagWarning> warnings,
        List<RagStageDiagnostic> stageDiagnostics
) {
    /** 紧凑构造器：将可能为 null 的警告与阶段诊断列表归一化为不可变空列表。 */
    public RagOutcome {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        stageDiagnostics = stageDiagnostics == null ? List.of() : List.copyOf(stageDiagnostics);
    }

    /** 构造成功类结果：不带警告，只记录单个阶段的诊断。 */
    public static <T> RagOutcome<T> of(RagOutcomeStatus status, T data, String stage,
                                       long durationMs, long itemCount) {
        return new RagOutcome<>(status, data, List.of(),
                List.of(new RagStageDiagnostic(stage, status, durationMs, itemCount)));
    }

    /** 构造降级结果：保留数据与阶段诊断，并附一条面向调用方的降级警告。 */
    public static <T> RagOutcome<T> degraded(T data, String stage, String code,
                                             String message, long durationMs, long itemCount) {
        return new RagOutcome<>(RagOutcomeStatus.DEGRADED, data,
                List.of(new RagWarning(stage, code, message, durationMs)),
                List.of(new RagStageDiagnostic(stage, RagOutcomeStatus.DEGRADED, durationMs, itemCount)));
    }

    /** 构造失败结果：保留可能的部分数据，记录失败阶段的警告与诊断，产出数量计为 0。 */
    public static <T> RagOutcome<T> failed(T data, String stage, String code,
                                           String message, long durationMs) {
        return new RagOutcome<>(RagOutcomeStatus.FAILED, data,
                List.of(new RagWarning(stage, code, message, durationMs)),
                List.of(new RagStageDiagnostic(stage, RagOutcomeStatus.FAILED, durationMs, 0)));
    }
}
