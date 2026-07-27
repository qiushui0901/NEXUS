package com.example.requirementrag.conflict;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Typed contracts for version-scoped, evidence-backed knowledge conflict analysis. */
public final class KnowledgeConflictModels {
    private KnowledgeConflictModels() {
    }

    public enum SourceType {
        REQUIREMENT,
        CODE,
        TEST,
        WIKI
    }

    public enum Authority {
        PRIMARY,
        DERIVED
    }

    public enum ConflictType {
        REQUIREMENT_CODE,
        REQUIREMENT_TEST,
        CODE_TEST,
        WIKI_PRIMARY,
        SOURCE_INTERNAL,
        VERSION_CONTAMINATION,
        PROJECT_CONTAMINATION,
        WIKI_MISSING_PRIMARY_EVIDENCE
    }

    public enum Severity {
        INFO,
        WARNING,
        ERROR,
        BLOCKING
    }

    public enum ResolutionStatus {
        OPEN
    }

    public enum ReportStatus {
        CLEAR,
        REVIEW_REQUIRED,
        BLOCKED
    }

    /** A bounded pointer to primary or derived source material. */
    public record KnowledgeEvidence(
            @NotBlank String evidenceId,
            String title,
            String source,
            String location,
            String excerpt
    ) {
    }

    /** A structured fact. Semantic equivalence is declared through the stable factKey, never guessed here. */
    public record KnowledgeClaim(
            String claimId,
            @NotBlank String projectId,
            @NotBlank String version,
            @NotBlank String factKey,
            @NotBlank String value,
            @NotNull SourceType sourceType,
            Authority authority,
            @Valid @NotNull KnowledgeEvidence evidence,
            List<String> supportingEvidenceIds
    ) {
        public KnowledgeClaim {
            supportingEvidenceIds = supportingEvidenceIds == null ? List.of() : List.copyOf(supportingEvidenceIds);
        }
    }

    public record AnalyzeRequest(
            @NotBlank String projectId,
            @NotBlank String targetVersion,
            @NotNull List<@Valid KnowledgeClaim> claims
    ) {
        public AnalyzeRequest {
            claims = claims == null ? List.of() : List.copyOf(claims);
        }
    }

    public record KnowledgeConflict(
            String conflictId,
            ConflictType type,
            Severity severity,
            ResolutionStatus resolutionStatus,
            String factKey,
            String message,
            List<KnowledgeClaim> claims
    ) {
        public KnowledgeConflict {
            claims = claims == null ? List.of() : List.copyOf(claims);
        }
    }

    public record KnowledgeConflictReport(
            String projectId,
            String targetVersion,
            ReportStatus status,
            int claimCount,
            int conflictCount,
            List<KnowledgeConflict> conflicts,
            List<String> warnings
    ) {
        public KnowledgeConflictReport {
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public static KnowledgeConflictReport empty(String projectId, String targetVersion) {
            return new KnowledgeConflictReport(projectId, targetVersion, ReportStatus.CLEAR,
                    0, 0, List.of(), List.of());
        }
    }
}
