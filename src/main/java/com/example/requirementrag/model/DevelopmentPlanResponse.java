package com.example.requirementrag.model;

import java.util.List;

/**
 * 结合需求文档向量库与代码向量库生成的开发入手建议。
 */
public record DevelopmentPlanResponse(
        String query,
        String documentId,
        String version,
        String summary,
        List<String> productUnderstanding,
        List<String> developmentConstraints,
        SimilarModule similarModule,
        List<String> chainOverview,
        List<PlanSection> sections,
        List<String> implementationOrder,
        List<String> steps,
        List<String> risks,
        List<DocumentReference> documentReferences,
        List<CodeChunk> codeReferences
) {
    /** 最接近当前需求的现有模块，用于复用链路。 */
    public record SimilarModule(String name, String reason, List<CodeChunk> references) {
    }

    /** 开发分析中的一个环节：先看什么、为什么看、要改什么。 */
    public record PlanSection(String title, String purpose, List<CodeChunk> inspectTargets,
                              List<String> keyQuestions, List<String> changeSuggestions) {
    }

    /** 需求文档命中片段。 */
    public record DocumentReference(String filename, String excerpt) {
    }
}
