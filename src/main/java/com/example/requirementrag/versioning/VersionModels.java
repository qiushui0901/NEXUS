package com.example.requirementrag.versioning;

import com.example.requirementrag.code.GitDiffService.GitDiffResult;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.wiki.WikiModels.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Public contracts for version manifests and multi-source comparison reports. */
public final class VersionModels {
    private VersionModels() {}

    public enum ManifestStatus { DRAFT, BASELINED, RELEASED, ARCHIVED }
    public enum TestRunStatus { PASSED, FAILED, PARTIAL, NOT_RUN }
    public enum TestCaseStatus { PASSED, FAILED, SKIPPED, NOT_RUN }
    public enum Availability { AVAILABLE, NOT_AVAILABLE }
    public enum ChangeType { ADDED, MODIFIED, REMOVED }

    public record TestCaseSnapshot(
            @NotBlank @Size(max = 200) String caseId,
            @Size(max = 300) String name,
            TestCaseStatus status
    ) {}

    public record TestSnapshot(
            @Size(max = 200) String reportId,
            TestRunStatus status,
            int total,
            int passed,
            int failed,
            int skipped,
            List<@Valid TestCaseSnapshot> cases
    ) {
        public TestSnapshot {
            cases = cases == null ? List.of() : List.copyOf(cases);
        }
    }

    public record VersionManifest(
            Integer schemaVersion,
            @NotBlank @Size(max = 100) String projectId,
            @NotBlank @Size(max = 100) String version,
            @Size(max = 100) String baseVersion,
            @Size(max = 160) String requirementDocumentId,
            @Size(max = 100) String requirementVersion,
            @Size(max = 64) String baseCodeCommit,
            @Size(max = 64) String codeCommit,
            @Valid TestSnapshot testSnapshot,
            @Size(max = 100) String wikiVersion,
            @Size(max = 200) String wikiBuildId,
            ManifestStatus status,
            String createdAt,
            String updatedAt,
            List<@Size(max = 500) String> notes
    ) {
        public VersionManifest {
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    public record RequirementChange(
            ChangeType type,
            String filename,
            String parentId,
            int parentOrder,
            String beforeHash,
            String afterHash,
            String beforeExcerpt,
            String afterExcerpt
    ) {}

    public record RequirementDiff(
            Availability availability,
            int added,
            int modified,
            int removed,
            List<RequirementChange> changes
    ) {
        public RequirementDiff {
            changes = changes == null ? List.of() : List.copyOf(changes);
        }
        public static RequirementDiff unavailable() {
            return new RequirementDiff(Availability.NOT_AVAILABLE, 0, 0, 0, List.of());
        }
    }

    public record TestCaseChange(
            ChangeType type,
            String caseId,
            String name,
            TestCaseStatus beforeStatus,
            TestCaseStatus afterStatus
    ) {}

    public record TestDiff(
            Availability availability,
            TestRunStatus beforeStatus,
            TestRunStatus afterStatus,
            int totalDelta,
            int passedDelta,
            int failedDelta,
            int skippedDelta,
            List<TestCaseChange> cases
    ) {
        public TestDiff {
            cases = cases == null ? List.of() : List.copyOf(cases);
        }
        public static TestDiff unavailable() {
            return new TestDiff(Availability.NOT_AVAILABLE, null, null, 0, 0, 0, 0, List.of());
        }
    }

    public record WikiPageChange(
            ChangeType type,
            String featureId,
            String title,
            Status beforeStatus,
            Status afterStatus,
            int evidenceDelta,
            boolean summaryChanged
    ) {}

    public record WikiDiff(
            Availability availability,
            int added,
            int modified,
            int removed,
            List<WikiPageChange> pages
    ) {
        public WikiDiff {
            pages = pages == null ? List.of() : List.copyOf(pages);
        }
        public static WikiDiff unavailable() {
            return new WikiDiff(Availability.NOT_AVAILABLE, 0, 0, 0, List.of());
        }
    }

    public record VersionComparisonReport(
            String projectId,
            String fromVersion,
            String toVersion,
            String generatedAt,
            RequirementDiff requirements,
            GitDiffResult code,
            TestDiff tests,
            WikiDiff wiki,
            List<RagWarning> warnings
    ) {
        public VersionComparisonReport {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
