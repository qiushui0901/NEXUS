package com.example.requirementrag.conflict;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.ConflictType;
import com.example.requirementrag.conflict.KnowledgeConflictModels.KnowledgeClaim;
import com.example.requirementrag.conflict.KnowledgeConflictModels.KnowledgeEvidence;
import com.example.requirementrag.conflict.KnowledgeConflictModels.ReportStatus;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeConflictServiceTest {
    private final KnowledgeConflictService service = new KnowledgeConflictService();

    @Test
    void keepsAlignedPrimarySourcesClear() {
        var report = service.analyze("sample", "2.1", List.of(
                claim("req-1", SourceType.REQUIREMENT, "enabled", "yes", List.of()),
                claim("code-1", SourceType.CODE, "enabled", "yes", List.of())));

        assertThat(report.status()).isEqualTo(ReportStatus.CLEAR);
        assertThat(report.conflicts()).isEmpty();
    }

    @Test
    void classifiesCrossSourceAndInternalConflicts() {
        var report = service.analyze("sample", "2.1", List.of(
                claim("req-1", SourceType.REQUIREMENT, "retry-policy", "allowed", List.of()),
                claim("code-1", SourceType.CODE, "retry-policy", "blocked", List.of()),
                claim("code-2", SourceType.CODE, "retry-policy", "queued", List.of())));

        assertThat(report.status()).isEqualTo(ReportStatus.REVIEW_REQUIRED);
        assertThat(report.conflicts()).extracting(conflict -> conflict.type())
                .contains(ConflictType.REQUIREMENT_CODE, ConflictType.SOURCE_INTERNAL);
    }

    @Test
    void classifiesRequirementTestAndCodeTestConflicts() {
        var report = service.analyze("sample", "2.1", List.of(
                claim("req-1", SourceType.REQUIREMENT, "result-state", "accepted", List.of()),
                claim("code-1", SourceType.CODE, "result-state", "pending", List.of()),
                claim("test-1", SourceType.TEST, "result-state", "rejected", List.of())));

        assertThat(report.status()).isEqualTo(ReportStatus.REVIEW_REQUIRED);
        assertThat(report.conflicts()).extracting(conflict -> conflict.type())
                .contains(ConflictType.REQUIREMENT_TEST, ConflictType.CODE_TEST);
    }

    @Test
    void blocksVersionAndProjectContamination() {
        KnowledgeClaim wrongVersion = claim("req-old", SourceType.REQUIREMENT, "state", "open", List.of());
        wrongVersion = new KnowledgeClaim(wrongVersion.claimId(), "sample", "2.0", wrongVersion.factKey(),
                wrongVersion.value(), wrongVersion.sourceType(), wrongVersion.authority(),
                wrongVersion.evidence(), wrongVersion.supportingEvidenceIds());
        KnowledgeClaim wrongProject = new KnowledgeClaim("code-other", "other", "2.1", "state", "open",
                SourceType.CODE, Authority.PRIMARY, evidence("code-other"), List.of());

        var report = service.analyze("sample", "2.1", List.of(wrongVersion, wrongProject));

        assertThat(report.status()).isEqualTo(ReportStatus.BLOCKED);
        assertThat(report.conflicts()).extracting(conflict -> conflict.type())
                .containsExactlyInAnyOrder(ConflictType.VERSION_CONTAMINATION, ConflictType.PROJECT_CONTAMINATION);
    }

    @Test
    void doesNotDeduplicateClaimsAcrossVersions() {
        KnowledgeClaim current = claim("req-1", SourceType.REQUIREMENT, "enabled", "yes", List.of());
        KnowledgeClaim stale = new KnowledgeClaim(current.claimId(), current.projectId(), "2.0",
                current.factKey(), current.value(), current.sourceType(), current.authority(),
                current.evidence(), current.supportingEvidenceIds());

        var report = service.analyze("sample", "2.1", List.of(current, stale));

        assertThat(report.claimCount()).isEqualTo(2);
        assertThat(report.status()).isEqualTo(ReportStatus.BLOCKED);
        assertThat(report.conflicts()).extracting(conflict -> conflict.type())
                .containsExactly(ConflictType.VERSION_CONTAMINATION);
    }

    @Test
    void mergesSupportingEvidenceWhenDeduplicatingWikiClaims() {
        KnowledgeClaim primary = claim("req-1", SourceType.REQUIREMENT, "enabled", "yes", List.of());
        KnowledgeClaim unsupported = claim("wiki-1", SourceType.WIKI, "enabled", "yes", List.of());
        KnowledgeClaim supported = claim("wiki-1", SourceType.WIKI, "enabled", "yes", List.of("req-1"));

        var report = service.analyze("sample", "2.1", List.of(primary, unsupported, supported));

        assertThat(report.claimCount()).isEqualTo(2);
        assertThat(report.conflicts()).isEmpty();
        assertThat(report.status()).isEqualTo(ReportStatus.REVIEW_REQUIRED);
        assertThat(report.warnings()).containsExactly("已合并 1 条重复声明");
    }

    @Test
    void blocksWikiWithoutPrimaryEvidenceAndMarksStaleWiki() {
        KnowledgeClaim requirement = claim("req-1", SourceType.REQUIREMENT, "visibility", "visible", List.of());
        KnowledgeClaim wiki = claim("wiki-1", SourceType.WIKI, "visibility", "hidden", List.of("req-1"));
        KnowledgeClaim unsupportedWiki = claim("wiki-2", SourceType.WIKI, "timeout", "30s", List.of());

        var report = service.analyze("sample", "2.1", List.of(requirement, wiki, unsupportedWiki));

        assertThat(report.status()).isEqualTo(ReportStatus.BLOCKED);
        assertThat(report.conflicts()).extracting(conflict -> conflict.type())
                .contains(ConflictType.WIKI_PRIMARY, ConflictType.WIKI_MISSING_PRIMARY_EVIDENCE);
        assertThat(report.conflicts().stream()
                .filter(conflict -> conflict.type() == ConflictType.WIKI_PRIMARY)
                .findFirst().orElseThrow().message()).contains("Wiki");
    }

    @Test
    void deduplicatesIdenticalClaimsAndReportsTheNormalization() {
        KnowledgeClaim claim = claim("req-1", SourceType.REQUIREMENT, "enabled", "yes", List.of());

        var report = service.analyze("sample", "2.1", List.of(claim, claim));

        assertThat(report.claimCount()).isEqualTo(1);
        assertThat(report.status()).isEqualTo(ReportStatus.REVIEW_REQUIRED);
        assertThat(report.warnings()).containsExactly("已合并 1 条重复声明");
    }

    @Test
    void legacyTestMapsToTestCaseAndNewSourceTypesParse() {
        assertThat(SourceType.normalize("TEST")).isEqualTo(SourceType.TEST_CASE);
        assertThat(SourceType.normalize("test_case")).isEqualTo(SourceType.TEST_CASE);
        assertThat(SourceType.normalize("PARAMETER_TABLE")).isEqualTo(SourceType.PARAMETER_TABLE);
        assertThat(SourceType.normalize("DOUBT")).isEqualTo(SourceType.DOUBT);
        assertThat(SourceType.normalize("unknown-source")).isNull();
        assertThat(SourceType.TEST.normalized()).isEqualTo(SourceType.TEST_CASE);
        assertThat(SourceType.TEST_CASE.normalized()).isEqualTo(SourceType.TEST_CASE);
        assertThat(Authority.SECONDARY).isEqualTo(Authority.SECONDARY);
    }

    @Test
    void legacyTestInputIsBackfilledToTestCaseInReport() {
        var report = service.analyze("sample", "2.1", List.of(
                claim("req-1", SourceType.REQUIREMENT, "result-state", "accepted", List.of()),
                claim("test-1", SourceType.TEST, "result-state", "rejected", List.of())));

        assertThat(report.status()).isEqualTo(ReportStatus.REVIEW_REQUIRED);
        assertThat(report.warnings()).contains("已将 1 条旧 TEST 来源声明规范化为 TEST_CASE");
        assertThat(report.conflicts()).anyMatch(conflict ->
                conflict.type() == ConflictType.REQUIREMENT_TEST
                        && conflict.claims().stream().anyMatch(claim ->
                        claim.sourceType() == SourceType.TEST_CASE
                                && claim.claimId().startsWith("test_case:")));
    }

    @Test
    void classifiesNewTestCaseAndTestResultAsTestConflicts() {
        var report = service.analyze("sample", "2.1", List.of(
                claim("req-1", SourceType.REQUIREMENT, "enabled", "yes", List.of()),
                claim("tc-1", SourceType.TEST_CASE, "enabled", "no", List.of())));
        assertThat(report.conflicts()).anyMatch(conflict ->
                conflict.type() == ConflictType.REQUIREMENT_TEST);
    }

    private KnowledgeClaim claim(String evidenceId, SourceType sourceType, String factKey,
                                 String value, List<String> supportingEvidenceIds) {
        Authority authority = sourceType == SourceType.WIKI ? Authority.DERIVED : Authority.PRIMARY;
        return new KnowledgeClaim(null, "sample", "2.1", factKey, value, sourceType, authority,
                evidence(evidenceId), supportingEvidenceIds);
    }

    private KnowledgeEvidence evidence(String evidenceId) {
        return new KnowledgeEvidence(evidenceId, "通用证据", "sample.txt", "section-1", "匿名化证据摘录");
    }
}
