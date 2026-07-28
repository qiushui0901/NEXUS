package com.example.requirementrag.model;

import com.example.requirementrag.code.CodeRelation;
import com.example.requirementrag.code.CodeSymbol;

import java.util.List;

/** Bounded graph/impact response with explicit degradation. */
public record CodeIntelligenceResponse(String availability, String projectId, String commitSha,
                                       List<CodeSymbol> roots, List<CodeSymbol> certainImpact,
                                       List<CodeSymbol> inferredImpact, List<CodeRelation> relations,
                                       List<CodeRelation> unresolvedCalls, List<String> changedFiles,
                                       List<String> regressionSuggestions, List<String> warnings,
                                       boolean truncated) {
}
