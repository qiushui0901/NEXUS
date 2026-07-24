package com.example.requirementrag.versioning;

import com.example.requirementrag.code.GitDiffService;
import com.example.requirementrag.code.GitDiffService.GitDiffResult;
import com.example.requirementrag.versioning.VersionModels.Availability;
import com.example.requirementrag.versioning.VersionModels.ManifestStatus;
import com.example.requirementrag.versioning.VersionModels.RequirementDiff;
import com.example.requirementrag.versioning.VersionModels.TestCaseSnapshot;
import com.example.requirementrag.versioning.VersionModels.TestCaseStatus;
import com.example.requirementrag.versioning.VersionModels.TestRunStatus;
import com.example.requirementrag.versioning.VersionModels.TestSnapshot;
import com.example.requirementrag.versioning.VersionModels.VersionManifest;
import com.example.requirementrag.wiki.WikiModels.PageSummary;
import com.example.requirementrag.wiki.WikiModels.Status;
import com.example.requirementrag.wiki.WikiModels.VersionIndex;
import com.example.requirementrag.wiki.WikiRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VersionComparisonServiceTest {
    @Test
    void combinesTestAndWikiChangesWithAvailableRequirementAndCodeDiffs() throws Exception {
        VersionManifestService manifests = mock(VersionManifestService.class);
        RequirementVersionDiffService requirements = mock(RequirementVersionDiffService.class);
        GitDiffService git = mock(GitDiffService.class);
        WikiRepository wiki = mock(WikiRepository.class);
        VersionManifest from = manifest("5.0", snapshot(TestCaseStatus.PASSED, 1, 1, 0));
        VersionManifest to = manifest("5.1", snapshot(TestCaseStatus.FAILED, 2, 1, 1));
        when(manifests.get("game", "5.0")).thenReturn(from);
        when(manifests.get("game", "5.1")).thenReturn(to);
        when(requirements.compare("game", from, to)).thenReturn(new RequirementDiff(
                Availability.AVAILABLE, 1, 0, 0, List.of()));
        when(git.diff("game", "aaaaaaa", "bbbbbbb")).thenReturn(new GitDiffResult(
                GitDiffService.Availability.AVAILABLE, 1, 0, 1, 0, 0, 1, 1, 0, List.of()));
        when(wiki.findIndex("game", "5.0")).thenReturn(Optional.of(index("5.0",
                new PageSummary("feature-a", "Feature A", "domain", "5.0", Status.DRAFT,
                        "old", List.of(), 1))));
        when(wiki.findIndex("game", "5.1")).thenReturn(Optional.of(index("5.1",
                new PageSummary("feature-a", "Feature A", "domain", "5.0", Status.CODE_VERIFIED,
                        "new", List.of(), 2),
                new PageSummary("feature-b", "Feature B", "domain", "5.1", Status.DRAFT,
                        "added", List.of(), 1))));

        var report = new VersionComparisonService(manifests, requirements, git, wiki)
                .compare("game", "5.0", "5.1");

        assertThat(report.requirements().added()).isEqualTo(1);
        assertThat(report.code().changedFiles()).isEqualTo(1);
        assertThat(report.tests().failedDelta()).isEqualTo(1);
        assertThat(report.tests().cases()).hasSize(1);
        assertThat(report.wiki().added()).isEqualTo(1);
        assertThat(report.wiki().modified()).isEqualTo(1);
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void marksMissingOptionalSourcesAsUnavailableWithSafeWarnings() throws Exception {
        VersionManifestService manifests = mock(VersionManifestService.class);
        RequirementVersionDiffService requirements = mock(RequirementVersionDiffService.class);
        GitDiffService git = mock(GitDiffService.class);
        WikiRepository wiki = mock(WikiRepository.class);
        VersionManifest from = new VersionManifest(1, "game", "5.0", null, null, null,
                null, null, null, null, null, ManifestStatus.DRAFT, null, null, List.of());
        VersionManifest to = new VersionManifest(1, "game", "5.1", "5.0", null, null,
                null, null, null, null, null, ManifestStatus.DRAFT, null, null, List.of());
        when(manifests.get("game", "5.0")).thenReturn(from);
        when(manifests.get("game", "5.1")).thenReturn(to);
        when(requirements.compare("game", from, to)).thenReturn(RequirementDiff.unavailable());
        when(wiki.findIndex("game", "5.0")).thenReturn(Optional.empty());
        when(wiki.findIndex("game", "5.1")).thenReturn(Optional.empty());

        var report = new VersionComparisonService(manifests, requirements, git, wiki)
                .compare("game", "5.0", "5.1");

        assertThat(report.requirements().availability()).isEqualTo(Availability.NOT_AVAILABLE);
        assertThat(report.code().availability()).isEqualTo(GitDiffService.Availability.NOT_AVAILABLE);
        assertThat(report.tests().availability()).isEqualTo(Availability.NOT_AVAILABLE);
        assertThat(report.wiki().availability()).isEqualTo(Availability.NOT_AVAILABLE);
        assertThat(report.warnings()).extracting(warning -> warning.code())
                .containsExactlyInAnyOrder("REQUIREMENT_REFERENCE_MISSING", "CODE_COMMIT_MISSING",
                        "TEST_SNAPSHOT_MISSING", "WIKI_VERSION_MISSING");
    }

    private VersionManifest manifest(String version, TestSnapshot snapshot) {
        return new VersionManifest(1, "game", version, "5.1".equals(version) ? "5.0" : null,
                "requirements", version, "5.1".equals(version) ? "aaaaaaa" : null,
                "5.1".equals(version) ? "bbbbbbb" : "aaaaaaa", snapshot, version, null,
                ManifestStatus.DRAFT, null, null, List.of());
    }

    private TestSnapshot snapshot(TestCaseStatus status, int total, int passed, int failed) {
        return new TestSnapshot("report", failed == 0 ? TestRunStatus.PASSED : TestRunStatus.FAILED,
                total, passed, failed, 0, List.of(new TestCaseSnapshot("case-a", "Case A", status)));
    }

    private VersionIndex index(String version, PageSummary... pages) {
        return new VersionIndex(1, "game", "Game", version, version, "", "", "now", List.of(pages));
    }
}
