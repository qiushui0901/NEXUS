package com.example.requirementrag.wiki;

import java.util.List;

/** Typed contracts shared by Wiki source files, generated artifacts and APIs. */
public final class WikiModels {
    private WikiModels() {}

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

    public record Relation(
            String targetFeatureId,
            String type,
            String label,
            String description
    ) {}

    /** A bounded, reviewable pointer to one requirement entry; never stores the source document itself. */
    public record RequirementSource(
            String documentId,
            String entryId,
            String filename,
            String version,
            String location,
            String contentHash,
            String verificationStatus
    ) {}

    /** A code location proven to exist at the selected commit. */
    public record CodeEntry(
            String role,
            String filePath,
            String symbol,
            String commit,
            String changeType,
            String verificationStatus
    ) {}

    /** Test knowledge keeps suggestions separate from real execution evidence. */
    public record TestKnowledge(
            String executionStatus,
            String executionReference,
            String summary,
            List<String> cases
    ) {}

    public record VersionChange(
            String changeType,
            String baseVersion,
            String version,
            String summary
    ) {}

    public record KnowledgeQuality(
            String reviewStatus,
            int requirementEvidenceCount,
            int codeEvidenceCount,
            boolean realTestExecution,
            List<String> missing
    ) {}

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
            List<Evidence> evidence
    ) {}

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
            String markdownPath
    ) {}

    public record PageSummary(
            String featureId,
            String title,
            String category,
            String introducedVersion,
            Status status,
            String summary,
            List<String> aliases,
            int evidenceCount
    ) {}

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

    public record ProjectSummary(
            String projectId,
            String projectName,
            List<String> versions,
            int pageCount
    ) {}

    public record GenerationResult(
            String projectId,
            String version,
            int pageCount,
            String outputPath,
            String generatedAt
    ) {}
}
