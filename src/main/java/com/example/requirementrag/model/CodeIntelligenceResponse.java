package com.example.requirementrag.model;

import com.example.requirementrag.code.CodeRelation;
import com.example.requirementrag.code.CodeSymbol;

import java.util.List;

/**
 * 有界的代码图谱/影响分析响应，显式标记结果降级：certainImpact 为确定受影响的符号，
 * inferredImpact 为推断受影响的符号，unresolvedCalls 列出未解析的调用，truncated 标记结果是否被裁剪。
 */
public record CodeIntelligenceResponse(String availability, String projectId, String commitSha,
                                       List<CodeSymbol> roots, List<CodeSymbol> certainImpact,
                                       List<CodeSymbol> inferredImpact, List<CodeRelation> relations,
                                       List<CodeRelation> unresolvedCalls, List<String> changedFiles,
                                       List<String> regressionSuggestions, List<String> warnings,
                                       boolean truncated) {
}
