package com.example.requirementrag.versioning;

import com.example.requirementrag.code.GitDiffService;
import com.example.requirementrag.code.GitDiffService.GitDiffResult;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.versioning.VersionModels.Availability;
import com.example.requirementrag.versioning.VersionModels.ChangeType;
import com.example.requirementrag.versioning.VersionModels.TestCaseChange;
import com.example.requirementrag.versioning.VersionModels.TestCaseSnapshot;
import com.example.requirementrag.versioning.VersionModels.TestDiff;
import com.example.requirementrag.versioning.VersionModels.TestSnapshot;
import com.example.requirementrag.versioning.VersionModels.VersionComparisonReport;
import com.example.requirementrag.versioning.VersionModels.VersionManifest;
import com.example.requirementrag.versioning.VersionModels.WikiDiff;
import com.example.requirementrag.versioning.VersionModels.WikiPageChange;
import com.example.requirementrag.wiki.WikiModels.PageSummary;
import com.example.requirementrag.wiki.WikiModels.VersionIndex;
import com.example.requirementrag.wiki.WikiRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Aggregates requirement, code, test and Wiki changes for two recorded versions. */
@Service
public class VersionComparisonService {
    private final VersionManifestService manifests;
    private final RequirementVersionDiffService requirementDiffService;
    private final GitDiffService gitDiffService;
    private final WikiRepository wikiRepository;

    public VersionComparisonService(VersionManifestService manifests,
                                    RequirementVersionDiffService requirementDiffService,
                                    GitDiffService gitDiffService,
                                    WikiRepository wikiRepository) {
        this.manifests = manifests;
        this.requirementDiffService = requirementDiffService;
        this.gitDiffService = gitDiffService;
        this.wikiRepository = wikiRepository;
    }

    public VersionComparisonReport compare(String projectId, String fromVersion, String toVersion) {
        String project = VersionPathPolicy.identifier(projectId, "projectId");
        String fromId = VersionPathPolicy.identifier(fromVersion, "fromVersion");
        String toId = VersionPathPolicy.identifier(toVersion, "toVersion");
        if (fromId.equals(toId)) throw new IllegalArgumentException("对比版本不能相同");
        VersionManifest from = manifests.get(project, fromId);
        VersionManifest to = manifests.get(project, toId);
        List<RagWarning> warnings = new ArrayList<>();

        VersionModels.RequirementDiff requirements;
        try {
            requirements = requirementDiffService.compare(project, from, to);
            if (requirements.availability() == Availability.NOT_AVAILABLE) {
                warnings.add(warning("version.requirements", "REQUIREMENT_REFERENCE_MISSING", "需求版本引用不完整，未执行需求差异分析"));
            }
        } catch (RuntimeException exception) {
            requirements = VersionModels.RequirementDiff.unavailable();
            warnings.add(warning("version.requirements", "REQUIREMENT_DIFF_UNAVAILABLE", "需求差异暂不可用"));
        }

        GitDiffResult code = compareCode(project, from, to, warnings);
        TestDiff tests = compareTests(from.testSnapshot(), to.testSnapshot(), warnings);
        WikiDiff wiki = compareWiki(project, from, to, warnings);
        return new VersionComparisonReport(project, fromId, toId, Instant.now().toString(),
                requirements, code, tests, wiki, warnings);
    }

    private GitDiffResult compareCode(String project, VersionManifest from, VersionManifest to,
                                      List<RagWarning> warnings) {
        String fromCommit = from.codeCommit();
        if (!hasText(fromCommit) && from.version().equals(to.baseVersion())) fromCommit = to.baseCodeCommit();
        if (!hasText(fromCommit) || !hasText(to.codeCommit())) {
            warnings.add(warning("version.code", "CODE_COMMIT_MISSING", "代码 commit 引用不完整，未执行代码差异分析"));
            return GitDiffResult.unavailable();
        }
        try {
            return gitDiffService.diff(project, fromCommit, to.codeCommit());
        } catch (RuntimeException | java.io.IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            warnings.add(warning("version.code", "CODE_DIFF_UNAVAILABLE", "代码差异暂不可用"));
            return GitDiffResult.unavailable();
        }
    }

    private TestDiff compareTests(TestSnapshot before, TestSnapshot after, List<RagWarning> warnings) {
        if (before == null || after == null) {
            warnings.add(warning("version.tests", "TEST_SNAPSHOT_MISSING", "测试快照不完整，未执行测试差异分析"));
            return TestDiff.unavailable();
        }
        Map<String, TestCaseSnapshot> oldCases = cases(before.cases());
        Map<String, TestCaseSnapshot> newCases = cases(after.cases());
        List<TestCaseChange> changes = new ArrayList<>();
        for (TestCaseSnapshot current : newCases.values()) {
            TestCaseSnapshot baseline = oldCases.get(current.caseId());
            if (baseline == null) {
                changes.add(new TestCaseChange(ChangeType.ADDED, current.caseId(), current.name(), null, current.status()));
            } else if (baseline.status() != current.status() || !safe(baseline.name()).equals(safe(current.name()))) {
                changes.add(new TestCaseChange(ChangeType.MODIFIED, current.caseId(), current.name(), baseline.status(), current.status()));
            }
        }
        for (TestCaseSnapshot baseline : oldCases.values()) {
            if (!newCases.containsKey(baseline.caseId())) {
                changes.add(new TestCaseChange(ChangeType.REMOVED, baseline.caseId(), baseline.name(), baseline.status(), null));
            }
        }
        changes.sort(Comparator.comparing(TestCaseChange::caseId));
        return new TestDiff(Availability.AVAILABLE, before.status(), after.status(),
                after.total() - before.total(), after.passed() - before.passed(),
                after.failed() - before.failed(), after.skipped() - before.skipped(), changes);
    }

    private WikiDiff compareWiki(String project, VersionManifest from, VersionManifest to, List<RagWarning> warnings) {
        String beforeVersion = hasText(from.wikiVersion()) ? from.wikiVersion() : from.version();
        String afterVersion = hasText(to.wikiVersion()) ? to.wikiVersion() : to.version();
        try {
            Optional<VersionIndex> before = wikiRepository.findIndex(project, beforeVersion);
            Optional<VersionIndex> after = wikiRepository.findIndex(project, afterVersion);
            if (before.isEmpty() || after.isEmpty()) {
                warnings.add(warning("version.wiki", "WIKI_VERSION_MISSING", "Wiki 版本不完整，未执行 Wiki 差异分析"));
                return WikiDiff.unavailable();
            }
            return wikiChanges(before.get(), after.get());
        } catch (RuntimeException exception) {
            warnings.add(warning("version.wiki", "WIKI_DIFF_UNAVAILABLE", "Wiki 差异暂不可用"));
            return WikiDiff.unavailable();
        }
    }

    private WikiDiff wikiChanges(VersionIndex before, VersionIndex after) {
        Map<String, PageSummary> oldPages = pages(before.pages());
        Map<String, PageSummary> newPages = pages(after.pages());
        List<WikiPageChange> changes = new ArrayList<>();
        for (PageSummary current : newPages.values()) {
            PageSummary baseline = oldPages.get(current.featureId());
            if (baseline == null) {
                changes.add(new WikiPageChange(ChangeType.ADDED, current.featureId(), current.title(), null,
                        current.status(), current.evidenceCount(), true));
            } else {
                boolean summaryChanged = !safe(baseline.summary()).equals(safe(current.summary()));
                int evidenceDelta = current.evidenceCount() - baseline.evidenceCount();
                if (baseline.status() != current.status() || summaryChanged || evidenceDelta != 0) {
                    changes.add(new WikiPageChange(ChangeType.MODIFIED, current.featureId(), current.title(),
                            baseline.status(), current.status(), evidenceDelta, summaryChanged));
                }
            }
        }
        for (PageSummary baseline : oldPages.values()) {
            if (!newPages.containsKey(baseline.featureId())) {
                changes.add(new WikiPageChange(ChangeType.REMOVED, baseline.featureId(), baseline.title(),
                        baseline.status(), null, -baseline.evidenceCount(), true));
            }
        }
        changes.sort(Comparator.comparing(WikiPageChange::featureId));
        return new WikiDiff(Availability.AVAILABLE, count(changes, ChangeType.ADDED),
                count(changes, ChangeType.MODIFIED), count(changes, ChangeType.REMOVED), changes);
    }

    private Map<String, TestCaseSnapshot> cases(List<TestCaseSnapshot> values) {
        Map<String, TestCaseSnapshot> result = new LinkedHashMap<>();
        for (TestCaseSnapshot value : values) result.putIfAbsent(value.caseId(), value);
        return result;
    }

    private Map<String, PageSummary> pages(List<PageSummary> values) {
        Map<String, PageSummary> result = new LinkedHashMap<>();
        for (PageSummary value : values == null ? List.<PageSummary>of() : values) result.putIfAbsent(value.featureId(), value);
        return result;
    }

    private int count(List<WikiPageChange> changes, ChangeType type) {
        return (int) changes.stream().filter(change -> change.type() == type).count();
    }

    private RagWarning warning(String stage, String code, String message) {
        return new RagWarning(stage, code, message, 0);
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String safe(String value) { return value == null ? "" : value; }
}
