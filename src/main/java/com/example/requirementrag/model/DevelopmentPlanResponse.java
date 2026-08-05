package com.example.requirementrag.model;

import com.example.requirementrag.conflict.KnowledgeConflictModels.KnowledgeConflictReport;
import com.example.requirementrag.evidence.PlanCitationBundle;

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
        List<CodeChunk> codeReferences,
        RagOutcomeStatus status,
        List<RagWarning> warnings,
        List<RagStageDiagnostic> stageDiagnostics,
        KnowledgeConflictReport conflictReport,
        PlanCitationBundle citations
) {
    /** 规范化构造：conflictReport 与 citations 为 null 时补默认空值，避免下游空指针。 */
    public DevelopmentPlanResponse {
        conflictReport = conflictReport == null ? KnowledgeConflictReport.empty(null, version) : conflictReport;
        citations = citations == null ? PlanCitationBundle.empty() : citations;
    }

    /** 兼容旧构造器：供缺少引用（citations）字段的调用方使用。 */
    public DevelopmentPlanResponse(
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
            List<CodeChunk> codeReferences,
            RagOutcomeStatus status,
            List<RagWarning> warnings,
            List<RagStageDiagnostic> stageDiagnostics,
            KnowledgeConflictReport conflictReport
    ) {
        this(query, documentId, version, summary, productUnderstanding, developmentConstraints, similarModule,
                chainOverview, sections, implementationOrder, steps, risks, documentReferences, codeReferences,
                status, warnings, stageDiagnostics, conflictReport, PlanCitationBundle.empty());
    }

    /** 兼容旧构造器：供缺少冲突报告（conflictReport）字段的调用方使用。 */
    public DevelopmentPlanResponse(
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
            List<CodeChunk> codeReferences,
            RagOutcomeStatus status,
            List<RagWarning> warnings,
            List<RagStageDiagnostic> stageDiagnostics
    ) {
        this(query, documentId, version, summary, productUnderstanding, developmentConstraints, similarModule,
                chainOverview, sections, implementationOrder, steps, risks, documentReferences, codeReferences,
                status, warnings, stageDiagnostics, KnowledgeConflictReport.empty(null, version),
                PlanCitationBundle.empty());
    }

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
