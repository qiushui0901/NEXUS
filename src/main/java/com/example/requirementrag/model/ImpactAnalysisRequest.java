package com.example.requirementrag.model;

/** 影响分析请求模型：通过 symbol 选择符号影响分析，或通过 fromCommit/toCommit 选择提交区间影响分析。 */
public record ImpactAnalysisRequest(String projectId, String symbol, String fromCommit, String toCommit,
                                    Integer depth, Integer limit) {
}
