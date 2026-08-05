package com.example.requirementrag.versioning;

import com.example.requirementrag.code.GitDiffService.GitDiffResult;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.wiki.WikiModels.Status;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 版本清单与多源对比报告的对外契约。 */
public final class VersionModels {
    private VersionModels() {}

    /** 版本档案的生命周期状态。 */
    public enum ManifestStatus { DRAFT, BASELINED, RELEASED, ARCHIVED }
    /** 整次测试运行的总体状态。 */
    public enum TestRunStatus { PASSED, FAILED, PARTIAL, NOT_RUN }
    /** 单个测试用例的状态。 */
    public enum TestCaseStatus { PASSED, FAILED, SKIPPED, NOT_RUN }
    /** 差异分析结果是否可用。 */
    public enum Availability { AVAILABLE, NOT_AVAILABLE }
    /** 差异变化类型：新增、修改、删除。 */
    public enum ChangeType { ADDED, MODIFIED, REMOVED }

    /** 单个测试用例的快照。 */
    public record TestCaseSnapshot(
            @NotBlank @Size(max = 200) String caseId,
            @Size(max = 300) String name,
            TestCaseStatus status
    ) {}

    /** 一次测试运行的汇总统计与用例快照。 */
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

    /** 一个版本的全部事实引用（需求、代码、测试、Wiki），持久化为 JSON 清单。 */
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

    /** 单条需求父块的前后变化，含前后哈希与内容摘要。 */
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

    /** 需求差异分析结果，含三类计数与明细列表。 */
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

    /** 单个测试用例的状态变化。 */
    public record TestCaseChange(
            ChangeType type,
            String caseId,
            String name,
            TestCaseStatus beforeStatus,
            TestCaseStatus afterStatus
    ) {}

    /** 测试差异：前后状态、各统计差值及用例变化列表。 */
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

    /** 单个 Wiki 页面的变化，含状态、证据数差值及摘要是否变更的标记。 */
    public record WikiPageChange(
            ChangeType type,
            String featureId,
            String title,
            Status beforeStatus,
            Status afterStatus,
            int evidenceDelta,
            boolean summaryChanged
    ) {}

    /** Wiki 差异：三类计数与页面变化列表。 */
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

    /** 两个版本的聚合对比报告，含各维度结果与警告。 */
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
