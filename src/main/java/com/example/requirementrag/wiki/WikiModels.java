package com.example.requirementrag.wiki;

import java.util.List;

/** Wiki 源文件、生成产物与 API 共用的类型化契约。 */
public final class WikiModels {
    private WikiModels() {}

    /** Wiki 页面类型：功能页之外的稳定知识与版本知识页面。 */
    public enum PageType {
        OVERVIEW,
        MODULE,
        FEATURE,
        API,
        DATA,
        VERSION
    }

    /** 声明与证据之间的支持强度。 */
    public enum ClaimSupport {
        FULL,
        PARTIAL,
        INFERRED,
        UNSUPPORTED,
        CONFLICT
    }

    /** 功能页面的核验状态。 */
    public enum Status {
        DRAFT,
        REQUIREMENT_VERIFIED,
        CODE_VERIFIED,
        FULLY_VERIFIED,
        CONFLICT,
        STALE,
        MISSING_IMPLEMENTATION,
        UNDOCUMENTED_IMPLEMENTATION,
        REJECTED
    }

    /** 一条支撑页面结论的原始证据引用。 */
    public record Evidence(
            String type,
            String title,
            String source,
            String version,
            String location,
            String excerpt,
            String commit,
            String filePath,
            String symbol,
            String verificationStatus
    ) {}

    /** 声明级证据：一项具体结论及其证据引用，取代页面末尾的散列表。 */
    public record Claim(
            String claimId,
            String section,
            String text,
            ClaimSupport support,
            List<String> evidenceIds
    ) {}

    /** 页面到其他功能页面的关联。 */
    public record Relation(
            String targetFeatureId,
            String type,
            String label,
            String description
    ) {}

    /** 指向单条需求条目的有界、可审阅指针，绝不存储源文档本身。 */
    public record RequirementSource(
            String documentId,
            String entryId,
            String filename,
            String version,
            String location,
            String contentHash,
            String verificationStatus
    ) {}

    /** 在所选 commit 上证明存在的代码位置。 */
    public record CodeEntry(
            String role,
            String filePath,
            String symbol,
            String commit,
            String changeType,
            String verificationStatus
    ) {}

    /** 测试知识将建议与真实执行证据分开保存。 */
    public record TestKnowledge(
            String executionStatus,
            String executionReference,
            String summary,
            List<String> cases
    ) {}

    /** 结构化记录的功能版本变化。 */
    public record VersionChange(
            String changeType,
            String baseVersion,
            String version,
            String summary
    ) {}

    /** 知识质量评估：审阅状态、证据计数与缺失项。 */
    public record KnowledgeQuality(
            String reviewStatus,
            int requirementEvidenceCount,
            int codeEvidenceCount,
            boolean realTestExecution,
            List<String> missing
    ) {}

    /** 页面源定义，是生成 Wiki 的输入。 */
    public record PageSource(
            String featureId,
            String title,
            String category,
            String introducedVersion,
            Status status,
            List<String> aliases,
            String summary,
            List<RequirementSource> requirementSources,
            List<String> productRules,
            List<String> processSteps,
            List<CodeEntry> codeEntries,
            List<String> codeSymbols,
            List<String> dataImpacts,
            List<String> boundaryConditions,
            List<String> acceptanceCriteria,
            List<String> testPoints,
            TestKnowledge testKnowledge,
            VersionChange versionChange,
            KnowledgeQuality quality,
            List<String> risks,
            List<Relation> relations,
            List<Evidence> evidence,
            PageType pageType,
            List<Claim> claims
    ) {}

    /** 单个版本 Wiki 的源定义，含页面列表。 */
    public record VersionSource(
            int schemaVersion,
            String projectId,
            String projectName,
            String version,
            String requirementVersion,
            String baseCodeCommit,
            String codeCommit,
            String generatedAt,
            List<PageSource> pages
    ) {}

    /** 生成后的完整页面，含渲染后的 Markdown 相对路径。 */
    public record Page(
            String projectId,
            String projectName,
            String version,
            String requirementVersion,
            String baseCodeCommit,
            String codeCommit,
            String generatedAt,
            String featureId,
            String title,
            String category,
            String introducedVersion,
            Status status,
            List<String> aliases,
            String summary,
            List<RequirementSource> requirementSources,
            List<String> productRules,
            List<String> processSteps,
            List<CodeEntry> codeEntries,
            List<String> codeSymbols,
            List<String> dataImpacts,
            List<String> boundaryConditions,
            List<String> acceptanceCriteria,
            List<String> testPoints,
            TestKnowledge testKnowledge,
            VersionChange versionChange,
            KnowledgeQuality quality,
            List<String> risks,
            List<Relation> relations,
            List<Evidence> evidence,
            PageType pageType,
            List<Claim> claims,
            String markdownPath
    ) {}

    /** 页面索引摘要，用于版本对比与列表展示。 */
    public record PageSummary(
            String featureId,
            String title,
            String category,
            String introducedVersion,
            Status status,
            String summary,
            List<String> aliases,
            int evidenceCount,
            PageType pageType
    ) {}

    /** 单个版本发布后的索引，含全部页面摘要。 */
    public record VersionIndex(
            int schemaVersion,
            String projectId,
            String projectName,
            String version,
            String requirementVersion,
            String baseCodeCommit,
            String codeCommit,
            String generatedAt,
            List<PageSummary> pages
    ) {}

    /** 项目的 Wiki 概览：版本列表与总页数。 */
    public record ProjectSummary(
            String projectId,
            String projectName,
            List<String> versions,
            int pageCount
    ) {}

    /** 一次 Wiki 生成的返回结果。 */
    public record GenerationResult(
            String projectId,
            String version,
            int pageCount,
            String outputPath,
            String generatedAt
    ) {}
}
