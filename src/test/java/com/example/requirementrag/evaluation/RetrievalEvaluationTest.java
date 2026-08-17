package com.example.requirementrag.evaluation;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.RagStageDiagnostic;
import com.example.requirementrag.model.RagWarning;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalEvaluationTest {

    private static final String SHIGUANG_DATASET = "evaluation/retrieval-eval-shiguang-v1.jsonl";
    private static final String SHIGUANG_SHA256 =
            "ac9fbc906ed28a593597e6fa03fbe1dcd4091cc7d610190d0f5d0dbbae6494c8";
    private static final String ENTERPRISE_DATASET = "evaluation/retrieval-eval-enterprise-v2.jsonl";
    private static final String ENTERPRISE_COMMIT = "d29f32589c5bd7c190a23eb3a84f27f0069f312f";
    private static final String ENTERPRISE_SHA256 =
            "cb0bc61851faf1c3b2a694a6d25759f8044d727fbac4e1bfab328507aa9aa4f4";

    @Test
    void frozenShiguangGoldenSetHasFormalSizeProfilesCategoriesAndStableLabels() throws Exception {
        List<RetrievalEvaluationCase> cases = RetrievalEvaluationDataset.loadResource(SHIGUANG_DATASET);

        assertEquals(54, cases.size());
        assertEquals(Map.of(
                        RetrievalEvaluationCase.RetrievalProfile.DEVELOPMENT_PLAN, 30L,
                        RetrievalEvaluationCase.RetrievalProfile.REQUIREMENT_REVIEW, 12L,
                        RetrievalEvaluationCase.RetrievalProfile.WIKI_BUILD, 12L),
                cases.stream().collect(Collectors.groupingBy(
                        RetrievalEvaluationCase::profile, Collectors.counting())));
        for (String category : List.of("normal-recall", "version-leakage", "similar-feature",
                "empty-result", "dependency-degradation", "cross-project-contamination")) {
            assertTrue(cases.stream().anyMatch(value -> value.tags().contains(category)), category);
        }
        assertEquals(cases.size(), cases.stream().map(RetrievalEvaluationCase::id).distinct().count());
        assertEquals(cases.size(), cases.stream().map(RetrievalEvaluationCase::query).distinct().count());
        assertTrue(cases.stream().allMatch(value -> value.projectId().equals("shiguang-eval")));
        assertTrue(cases.stream().allMatch(value -> value.tags().contains("shiguang-real")));
        List<RetrievalEvaluationCase> codeCases = cases.stream()
                .filter(value -> !value.goldCode().isEmpty()).toList();
        assertEquals(42, codeCases.size());
        for (String platformTerm : List.of("开发计划", "构建 Wiki", "BGE", "Qdrant", "Embedding", "误召回")) {
            assertTrue(codeCases.stream().noneMatch(value -> value.query().contains(platformTerm)), platformTerm);
        }
        assertTrue(cases.stream().flatMap(value -> value.goldCode().stream()).allMatch(gold -> {
            String path = RetrievalEvaluationDataset.normalizePath(gold.filePath());
            return !path.startsWith("/") && !path.startsWith("../")
                    && !path.contains("/../") && !path.startsWith("src/test/resources/");
        }));

        try (InputStream input = getClass().getClassLoader().getResourceAsStream(SHIGUANG_DATASET)) {
            assertTrue(input != null);
            assertEquals(SHIGUANG_SHA256, sha256(input.readAllBytes()));
        }
    }

    @Test
    void rejectsDuplicateIdsWithJsonlLineNumber() {
        String input = validCase("duplicate") + "\n" + validCase("duplicate");

        RetrievalEvaluationDataset.DatasetValidationException error = assertThrows(
                RetrievalEvaluationDataset.DatasetValidationException.class,
                () -> RetrievalEvaluationDataset.parse(new StringReader(input)));

        assertTrue(error.getMessage().contains("line 2"));
        assertTrue(error.getMessage().contains("duplicate id"));
    }

    @Test
    void rejectsBlankQuery() {
        assertInvalid(validCase("blank-query").replace("真实问题", "   "),
                "line 1", "query must not be blank");
    }

    @Test
    void rejectsHitWithoutGold() {
        String input = validCase("missing-gold")
                .replace("[{\"filename\":\"5.1/example.html\",\"mustContain\":[\"证据\"]}]", "[]");
        assertInvalid(input, "line 1", "HIT requires");
    }

    @Test
    void rejectsNoResultsWithGold() {
        assertInvalid(validCase("bad-no-results").replace("\"HIT\"", "\"NO_RESULTS\""),
                "line 1", "NO_RESULTS must not contain Gold");
    }

    @Test
    void rejectsUnstablePointAndVectorIdentifiers() {
        String input = validCase("point-id")
                .replace("\"notes\":\"ok\"", "\"pointId\":\"123\",\"notes\":\"ok\"");
        assertInvalid(input, "line 1", "unstable point/vector identifiers");
    }

    @Test
    void rejectsInvalidCodePath() {
        String input = """
                {"id":"absolute-code-path","query":"真实问题","profile":"DEVELOPMENT_PLAN","projectId":"project",\
                "documentId":"doc","version":"1","expectedOutcome":"HIT","goldDocuments":[],\
                "goldCode":[{"projectId":"project","filePath":"/tmp/repo/File.java","symbolName":"run"}],\
                "tags":[],"notes":"ok"}
                """;

        assertInvalid(input, "line 1", "relative repository path");
    }

    @Test
    void rejectsInvalidProfileAndOutcomeAsLineScopedParseErrors() {
        assertInvalid(validCase("bad-profile").replace("DEVELOPMENT_PLAN", "UNKNOWN"),
                "line 1", "invalid JSON or enum value");
        assertInvalid(validCase("bad-outcome").replace("\"HIT\"", "\"MAYBE\""),
                "line 1", "invalid JSON or enum value");
    }

    @Test
    void rejectsInvalidStructuredDocumentPositions() {
        String structured = validCase("structured-position").replace(
                "\"mustContain\":[\"证据\"]",
                "\"parentOrder\":1,\"childOrder\":2,\"mustContain\":[\"证据\"]");

        assertInvalid(structured.replace("\"childOrder\":2", "\"childOrder\":-1"),
                "line 1", "childOrder must be non-negative");
        assertInvalid(structured.replace("\"parentOrder\":1,", ""),
                "line 1", "childOrder requires parentOrder");
    }

    @Test
    void preservesV1CompatibilityAndRejectsUnsupportedSchemaVersions() {
        RetrievalEvaluationCase legacy = RetrievalEvaluationDataset.parse(
                new StringReader(validCase("legacy-v1"))).getFirst();

        assertNull(legacy.schemaVersion());
        assertNull(legacy.queryType());
        assertNull(legacy.sourceCommit());
        assertNull(legacy.review());
        assertNull(legacy.goldDocuments().getFirst().evidenceId());

        assertInvalid(validCase("unsupported-schema")
                        .replace("\"expectedOutcome\"", "\"schemaVersion\":3,\"expectedOutcome\""),
                "line 1", "unsupported schemaVersion");
    }

    @Test
    void v2RequiresQueryTypeCommitApprovedReviewAndIsoTimestamp() {
        String valid = validV2Case("v2-contract");

        assertInvalid(valid.replace("\"queryType\":\"BUSINESS_SEMANTIC\",", ""),
                "schemaVersion 2 requires queryType");
        assertInvalid(valid.replace(ENTERPRISE_COMMIT, "ABC"),
                "40-character lowercase sourceCommit");
        assertInvalid(valid.replace("\"status\":\"APPROVED\"", "\"status\":\"REJECTED\""),
                "requires APPROVED review");
        assertInvalid(valid.replace("\"reviewer\":\"qa-team\"", "\"reviewer\":\" \""),
                "non-blank reviewer");
        assertInvalid(valid.replace("2026-08-17T10:00:00+08:00", "not-a-timestamp"),
                "ISO-8601 timestamp");
    }

    @Test
    void v2RequiresExactUniqueEvidenceIds() {
        assertInvalid(validV2Case("bad-evidence").replace(
                        "requirement:project:1:5.1/example.html:*:*",
                        "requirement:project:1:wrong.html:*:*"),
                "evidenceId must equal");

        String duplicateGold = "{\"filename\":\"5.1/example.html\","
                + "\"mustContain\":[\"证据\"],"
                + "\"evidenceId\":\"requirement:project:1:5.1/example.html:*:*\"}";
        String duplicate = validV2Case("duplicate-evidence").replace(
                "\"goldDocuments\":[" + duplicateGold + "]",
                "\"goldDocuments\":[" + duplicateGold + "," + duplicateGold + "]");
        assertInvalid(duplicate, "duplicate evidenceId");
    }

    @Test
    void multiGoldNdcgUsesTheActualRelevantItemCount() {
        List<RetrievalEvaluationCase.GoldDocument> gold = List.of(
                new RetrievalEvaluationCase.GoldDocument("one.md", null, List.of()),
                new RetrievalEvaluationCase.GoldDocument("two.md", null, List.of()));

        double ndcg = RetrievalEvaluationMatcher.documentNdcgAt(
                gold, List.of(chunk("one.md", 0, 0, "", "")), 10);

        double ideal = 1.0 + 1.0 / (Math.log(3.0) / Math.log(2.0));
        assertEquals(1.0 / ideal, ndcg, 0.000001);
    }

    @Test
    void legacyDocumentGoldUsesFileLevelMatchingWithoutContentAccumulation() {
        RetrievalEvaluationCase.GoldDocument gold = new RetrievalEvaluationCase.GoldDocument(
                "5.1/福利-成长基金.html", null,
                List.of("一键领取", "后端需幂等", "POST /growth-fund/claim-all"));
        List<ChunkRecord> candidates = List.of(
                chunk("other.html", 0, 0, "无关", "无关"),
                chunk("5.1/福利-成长基金.html", 2, 0, "一键领取", "后端需幂等"),
                chunk("5.1/福利-成长基金.html", 3, 0, "接口", "POST /growth-fund/claim-all"));

        assertEquals(2, RetrievalEvaluationMatcher.firstFileRank(List.of(gold), candidates, 10));
        assertEquals(2, RetrievalEvaluationMatcher.firstDocumentRank(List.of(gold), candidates, 10));
        assertNull(RetrievalEvaluationMatcher.firstSectionRank(List.of(gold), candidates, 10));
        assertNull(RetrievalEvaluationMatcher.firstChildRank(List.of(gold), candidates, 10));
    }

    @Test
    void fileSectionAndChildRanksStayIndependentAndChildCannotReadParentText() {
        RetrievalEvaluationCase.GoldDocument gold = new RetrievalEvaluationCase.GoldDocument(
                "5.1/福利-成长基金.html", 1, 2, List.of("显示红点"));
        List<ChunkRecord> candidates = List.of(
                chunk("5.1/福利-成长基金.html", 0, 2, "显示红点", "其他章节"),
                chunk("5.1/福利-成长基金.html", 1, 0, "显示红点", "错误子块"),
                chunk("5.1/福利-成长基金.html", 1, 2, "显示红点", "仍是错误子块"));

        assertEquals(1, RetrievalEvaluationMatcher.firstFileRank(List.of(gold), candidates, 10));
        assertEquals(2, RetrievalEvaluationMatcher.firstSectionRank(List.of(gold), candidates, 10));
        assertNull(RetrievalEvaluationMatcher.firstChildRank(List.of(gold), candidates, 10));
        assertNull(RetrievalEvaluationMatcher.firstDocumentRank(List.of(gold), candidates, 10));

        List<ChunkRecord> withCorrectChild = List.of(
                candidates.get(0), candidates.get(1),
                chunk("5.1/福利-成长基金.html", 1, 2, "无关父文本", "这里显示红点"));
        assertEquals(3, RetrievalEvaluationMatcher.firstChildRank(List.of(gold), withCorrectChild, 10));
        assertEquals(3, RetrievalEvaluationMatcher.firstDocumentRank(List.of(gold), withCorrectChild, 10));
    }

    @Test
    void codeMatcherNormalizesPathSeparatorsButRequiresStableLabels() {
        RetrievalEvaluationCase.GoldCode gold = new RetrievalEvaluationCase.GoldCode(
                "project", "module/src/main/java/example/Service.java", "run");
        List<CodeChunk> candidates = List.of(
                code("project", "module\\src\\main\\java\\example\\Service.java", "helper"),
                code("project", "module\\src\\main\\java\\example\\Service.java", "run"));

        assertEquals(2, RetrievalEvaluationMatcher.firstCodeRank(List.of(gold), candidates, 10));
        assertNull(RetrievalEvaluationMatcher.firstCodeRank(
                List.of(new RetrievalEvaluationCase.GoldCode("other", gold.filePath(), gold.symbolName())),
                candidates, 10));
    }

    @Test
    void noResultsFailsWhenEitherRetrieverReturnsCandidates() {
        RetrievalEvaluationCase noResults = RetrievalEvaluationDataset.loadResource(SHIGUANG_DATASET).stream()
                .filter(value -> value.expectedOutcome() == RetrievalEvaluationCase.ExpectedOutcome.NO_RESULTS)
                .findFirst().orElseThrow();

        RetrievalEvaluationMatcher.CaseResult failed = RetrievalEvaluationMatcher.evaluate(
                noResults, List.of(chunk("unrelated.html", 0, 0, "相似但不能回答", "")), List.of(), 1, 0, 1);
        RetrievalEvaluationMatcher.CaseResult passed = RetrievalEvaluationMatcher.evaluate(
                noResults, List.of(), List.of(), 1, 1, 2);

        assertFalse(failed.success());
        assertTrue(passed.success());
    }

    @Test
    void settingsFreezeModeOutputWarmupsRepetitionsAndOptionalBaseline() {
        RetrievalEvaluationSettings defaults = RetrievalEvaluationSettings.from(Map.of());
        assertEquals(RetrievalEvaluationSettings.EvaluationMode.RERANK_0_8, defaults.mode());
        assertEquals(Path.of("target", "retrieval-evaluation", "0.8-rerank"), defaults.outputDirectory());
        assertEquals(0, defaults.warmupRuns());
        assertEquals(1, defaults.repetitions());
        assertTrue(defaults.baselineResource().isEmpty());

        RetrievalEvaluationSettings baseline = RetrievalEvaluationSettings.from(Map.of(
                RetrievalEvaluationSettings.MODE_ENV, " 0.7-baseline ",
                RetrievalEvaluationSettings.OUTPUT_ENV, " target/custom-baseline ",
                RetrievalEvaluationSettings.WARMUP_ENV, "2",
                RetrievalEvaluationSettings.REPETITIONS_ENV, "3",
                RetrievalEvaluationSettings.BASELINE_ENV, " evaluation/baseline.json "));
        assertEquals(RetrievalEvaluationSettings.EvaluationMode.BASELINE_0_7, baseline.mode());
        assertEquals(Path.of("target/custom-baseline"), baseline.outputDirectory());
        assertEquals(2, baseline.warmupRuns());
        assertEquals(3, baseline.repetitions());
        assertEquals("evaluation/baseline.json", baseline.baselineResource().orElseThrow());

        RetrievalEvaluationSettings blankBaseline = RetrievalEvaluationSettings.from(Map.of(
                RetrievalEvaluationSettings.BASELINE_ENV, " "));
        assertTrue(blankBaseline.baselineResource().isEmpty());

        RetrievalEvaluationSettings documentV2 = RetrievalEvaluationSettings.from(Map.of(
                RetrievalEvaluationSettings.MODE_ENV, "0.8.2-document-v2",
                RetrievalEvaluationSettings.DATASET_ENV, "evaluation/retrieval-eval-document-v2.jsonl"));
        assertEquals(RetrievalEvaluationSettings.EvaluationMode.DOCUMENT_V2_0_8_2, documentV2.mode());
        assertEquals(Path.of("target", "retrieval-evaluation", "0.8.2-document-v2"),
                documentV2.outputDirectory());
        assertEquals("evaluation/retrieval-eval-document-v2.jsonl", documentV2.datasetResource());

        RetrievalEvaluationSettings enterprise = RetrievalEvaluationSettings.from(Map.of(
                RetrievalEvaluationSettings.MODE_ENV, "0.8.6-enterprise",
                RetrievalEvaluationSettings.DATASET_ENV, ENTERPRISE_DATASET,
                RetrievalEvaluationSettings.BASELINE_ENV,
                "evaluation/retrieval-threshold-enterprise-v0.8.6.json"));
        assertEquals(RetrievalEvaluationSettings.EvaluationMode.ENTERPRISE_0_8_6, enterprise.mode());
        assertEquals(Path.of("target", "retrieval-evaluation", "0.8.6-enterprise"),
                enterprise.outputDirectory());
        assertEquals(ENTERPRISE_DATASET, enterprise.datasetResource());
    }

    @Test
    void settingsRejectInvalidModeAndRunCounts() {
        assertThrows(IllegalArgumentException.class, () -> RetrievalEvaluationSettings.from(Map.of(
                RetrievalEvaluationSettings.MODE_ENV, "unknown")));
        assertThrows(IllegalArgumentException.class, () -> RetrievalEvaluationSettings.from(Map.of(
                RetrievalEvaluationSettings.WARMUP_ENV, "-1")));
        assertThrows(IllegalArgumentException.class, () -> RetrievalEvaluationSettings.from(Map.of(
                RetrievalEvaluationSettings.REPETITIONS_ENV, "0")));
        assertThrows(IllegalArgumentException.class, () -> RetrievalEvaluationSettings.from(Map.of(
                RetrievalEvaluationSettings.REPETITIONS_ENV, "not-a-number")));
    }

    @Test
    void matcherCountsOnlyBgeStageSuccessAndStableDegradationCode() {
        RetrievalEvaluationCase evaluationCase = documentCase("bge-contract", "gold.html", "gold");
        RetrievalEvaluationMatcher.CaseResult result = RetrievalEvaluationMatcher.evaluate(
                evaluationCase,
                List.of(chunk("gold.html", 0, 0, "gold", "")),
                List.of(), 2, 0, 2, null, null, 2,
                List.of(new RagWarning("retrieval.rerank", "BGE_RERANK_UNAVAILABLE", "degraded", 1)),
                List.of(
                        new RagStageDiagnostic("retrieval.rerank", RagOutcomeStatus.SUCCESS, 1, 1),
                        new RagStageDiagnostic("bge.rerank", RagOutcomeStatus.SUCCESS, 1, 1),
                        new RagStageDiagnostic("bge.rerank", RagOutcomeStatus.DEGRADED, 1, 1),
                        new RagStageDiagnostic("bge.rerank", RagOutcomeStatus.NO_RESULTS, 0, 0),
                        new RagStageDiagnostic("bge.rerank.singleton_skip", RagOutcomeStatus.SUCCESS, 0, 1)));

        assertEquals(2, result.repetition());
        assertEquals(2, result.bgeCalls());
        assertEquals(1, result.bgeSuccesses());
        assertEquals(1, result.bgeDegradations());
        assertEquals(1, result.bgeNoCandidateSkips());
        assertEquals(1, result.bgeSingletonSkips());
    }

    @Test
    void reportClassifiesFormalRunsAggregatesProfilesAndPrintsReciprocalRankRawValue() {
        List<RetrievalEvaluationCase> dataset = RetrievalEvaluationDataset.loadResource(SHIGUANG_DATASET);
        List<RetrievalEvaluationMatcher.CaseResult> formalResults = dataset.stream()
                .map(value -> RetrievalEvaluationMatcher.evaluate(value, List.of(), List.of(), 1, 0, 1))
                .toList();

        RetrievalEvaluationReport formal = RetrievalEvaluationReport.create(
                SHIGUANG_DATASET, "0.8-rerank", 1, 1, formalResults);

        assertEquals("0.8-rerank", formal.mode());
        assertEquals("formal", formal.classification());
        assertEquals(54, formal.datasetCaseCount());
        assertEquals(Set.of("DEVELOPMENT_PLAN", "REQUIREMENT_REVIEW", "WIKI_BUILD"), formal.profiles().keySet());
        assertEquals(30, formal.profiles().get("DEVELOPMENT_PLAN").totalCases());
        assertEquals(12, formal.profiles().get("REQUIREMENT_REVIEW").totalCases());
        assertEquals(12, formal.profiles().get("WIKI_BUILD").totalCases());

        RetrievalEvaluationCase rankOne = documentCase("rank-one", "one.html", "one");
        RetrievalEvaluationCase rankTwo = documentCase("rank-two", "two.html", "two");
        RetrievalEvaluationMatcher.CaseResult first = RetrievalEvaluationMatcher.evaluate(
                rankOne, List.of(chunk("one.html", 0, 0, "one", "")), List.of(), 3, 0, 3,
                null, null, 1, List.of(),
                List.of(new RagStageDiagnostic("bge.rerank", RagOutcomeStatus.SUCCESS, 1, 1)));
        RetrievalEvaluationMatcher.CaseResult second = RetrievalEvaluationMatcher.evaluate(
                rankTwo, List.of(chunk("other.html", 0, 0, "", ""),
                        chunk("two.html", 0, 0, "two", "")), List.of(), 7, 0, 7,
                null, null, 1,
                List.of(new RagWarning("retrieval.rerank", "BGE_RERANK_UNAVAILABLE", "degraded", 1)),
                List.of(new RagStageDiagnostic("bge.rerank", RagOutcomeStatus.DEGRADED, 1, 1)));
        RetrievalEvaluationReport calibration = RetrievalEvaluationReport.create(
                "test.jsonl", "0.8-rerank", 0, 1, List.of(first, second));

        assertEquals("calibration", calibration.classification());
        assertEquals(1.5, calibration.summary().reciprocalRankSum());
        assertEquals(0.75, calibration.summary().mrrAt10());
        assertEquals(2, calibration.summary().bgeCalls());
        assertEquals(1, calibration.summary().bgeSuccesses());
        assertEquals(1, calibration.summary().bgeDegradations());
        assertEquals(0, calibration.summary().bgeNoCandidateSkips());
        assertEquals(0, calibration.summary().bgeSingletonSkips());
        assertEquals(1, calibration.summary().infrastructureFailureCases());
        assertTrue(calibration.markdown().contains("1.500/2"));
        assertFalse(calibration.markdown().contains("2.000/2"));
    }

    @Test
    void uniqueCaseQualityDeduplicatesRepetitionsAndReportsLayerDenominators() {
        RetrievalEvaluationCase structured = new RetrievalEvaluationCase(
                "structured", "structured query",
                RetrievalEvaluationCase.RetrievalProfile.REQUIREMENT_REVIEW,
                "project", "doc", "1", RetrievalEvaluationCase.ExpectedOutcome.HIT,
                List.of(new RetrievalEvaluationCase.GoldDocument(
                        "structured.md", 1, 2, List.of("目标证据"))),
                List.of(), List.of("structured-gold"), "test");
        ChunkRecord correct = chunk("structured.md", 1, 2, "父文本", "目标证据");
        RetrievalEvaluationMatcher.CaseResult first = RetrievalEvaluationMatcher.evaluate(
                structured, List.of(correct), List.of(), 1, 0, 1,
                null, null, 1, List.of(), List.of());
        RetrievalEvaluationMatcher.CaseResult repeatedMiss = RetrievalEvaluationMatcher.evaluate(
                structured, List.of(), List.of(), 2, 0, 2,
                null, null, 2, List.of(), List.of());

        RetrievalEvaluationReport report = RetrievalEvaluationReport.create(
                "v2.jsonl", "0.8.2-document-v2", 0, 2, List.of(first, repeatedMiss));

        assertEquals(2, report.summary().totalCases());
        assertEquals(1, report.uniqueCaseSummary().totalCases());
        assertEquals(1, report.uniqueCaseSummary().fileCases());
        assertEquals(1, report.uniqueCaseSummary().fileHits());
        assertEquals(1, report.uniqueCaseSummary().sectionCases());
        assertEquals(1, report.uniqueCaseSummary().sectionHits());
        assertEquals(1, report.uniqueCaseSummary().childCases());
        assertEquals(1, report.uniqueCaseSummary().childHits());
        assertEquals(1.0, report.uniqueCaseSummary().mrrAt10());
        assertTrue(report.markdown().contains("## Unique case quality"));
        assertTrue(report.markdown().contains("|File Recall@10|1.000|1/1|"));
        assertTrue(report.markdown().contains("|Child Recall@10|1.000|1/1|"));
        assertTrue(report.markdown().contains("|Code Recall@10|N/A|0/0|"));
    }

    @Test
    void uniqueCaseDegradationUsesOneDeterministicExecutionPerCase() {
        RetrievalEvaluationCase evaluationCase = documentCase("degraded-once", "gold.md", "gold");
        RetrievalEvaluationMatcher.CaseResult first = RetrievalEvaluationMatcher.evaluate(
                evaluationCase, List.of(chunk("gold.md", 0, 0, "", "gold")), List.of(), 1, 0, 1,
                null, null, 1,
                List.of(new RagWarning("retrieval", "DEPENDENCY_DEGRADED", "degraded", 1)),
                List.of());
        RetrievalEvaluationMatcher.CaseResult repeated = RetrievalEvaluationMatcher.evaluate(
                evaluationCase, List.of(chunk("gold.md", 0, 0, "", "gold")), List.of(), 1, 0, 1,
                null, null, 2, List.of(), List.of());

        RetrievalEvaluationReport report = RetrievalEvaluationReport.create(
                "dataset.jsonl", "0.8.6-enterprise", 0, 2, List.of(first, repeated));

        assertEquals(2, report.summary().totalCases());
        assertEquals(1, report.uniqueCaseSummary().totalCases());
        assertEquals(1, report.uniqueCaseSummary().degradedCases());
        assertEquals(1.0, report.uniqueCaseSummary().degradationRate());
    }

    @Test
    void frozenEnterpriseDatasetHasAllQueryTypesApprovedProvenanceAndStableFingerprint() throws Exception {
        List<RetrievalEvaluationCase> cases = RetrievalEvaluationDataset.loadResource(ENTERPRISE_DATASET);

        assertEquals(24, cases.size());
        assertEquals(24, cases.stream().map(RetrievalEvaluationCase::query).distinct().count());
        assertEquals(Set.of(RetrievalEvaluationCase.QueryType.values()),
                cases.stream().map(RetrievalEvaluationCase::queryType).collect(Collectors.toSet()));
        assertTrue(cases.stream().allMatch(value -> Integer.valueOf(2).equals(value.schemaVersion())));
        assertTrue(cases.stream().allMatch(value -> ENTERPRISE_COMMIT.equals(value.sourceCommit())));
        assertTrue(cases.stream().allMatch(value -> value.review() != null
                && value.review().status() == RetrievalEvaluationCase.ReviewStatus.APPROVED));
        assertTrue(cases.stream().flatMap(value -> value.goldDocuments().stream())
                .allMatch(gold -> gold.evidenceId() != null && gold.evidenceId().startsWith("requirement:")));
        assertTrue(cases.stream().flatMap(value -> value.goldCode().stream())
                .allMatch(gold -> gold.evidenceId() != null && gold.evidenceId().startsWith("code:")));

        try (InputStream input = getClass().getClassLoader().getResourceAsStream(ENTERPRISE_DATASET)) {
            assertTrue(input != null);
            assertEquals(ENTERPRISE_SHA256, sha256(input.readAllBytes()));
        }
    }

    @Test
    void documentRecallCutoffsExposeWhenTop10MasksRankingMisses() throws Exception {
        RetrievalEvaluationCase structured = new RetrievalEvaluationCase(
                "cutoff-sensitive", "cutoff sensitive query",
                RetrievalEvaluationCase.RetrievalProfile.REQUIREMENT_REVIEW,
                "project", "doc", "1", RetrievalEvaluationCase.ExpectedOutcome.HIT,
                List.of(new RetrievalEvaluationCase.GoldDocument(
                        "structured.md", 1, 2, List.of("目标证据"))),
                List.of(), List.of("structured-gold"), "test");
        ChunkRecord distractor = chunk("distractor.md", 1, 2, "干扰父文本", "干扰证据");
        ChunkRecord correct = chunk("structured.md", 1, 2, "父文本", "目标证据");
        RetrievalEvaluationMatcher.CaseResult result = RetrievalEvaluationMatcher.evaluate(
                structured, List.of(distractor, correct), List.of(), 1, 0, 1,
                null, null, 1, List.of(), List.of());

        RetrievalEvaluationReport report = RetrievalEvaluationReport.create(
                "v2.jsonl", "0.8.2-document-v2", 0, 1, List.of(result));

        assertEquals(0.0, report.uniqueCaseSummary().fileRecallByCutoff().recallAt1());
        assertEquals(1.0, report.uniqueCaseSummary().fileRecallByCutoff().recallAt3());
        assertEquals(1.0, report.uniqueCaseSummary().fileRecallAt10());
        assertTrue(report.uniqueCaseSummary().top10MasksLowerCutoff());
        assertTrue(report.markdown().contains("|File|0.000 (0/1)|1.000 (1/1)|"));
        assertTrue(report.markdown().contains("Ranking sensitivity warning"));
        String json = new ObjectMapper().writeValueAsString(report);
        assertTrue(json.contains("\"fileRecallAt10\":1.0"));
        assertTrue(json.contains("\"fileRecallByCutoff\""));
        assertTrue(json.contains("\"recallAt1\":0.0"));
        assertTrue(json.contains("\"top10MasksLowerCutoff\":true"));
    }

    @Test
    void stageTraceExplainsRankMovementAndAttributesCandidateMisses() {
        RetrievalEvaluationCase mixed = new RetrievalEvaluationCase(
                "trace-mixed", "trace query", RetrievalEvaluationCase.RetrievalProfile.DEVELOPMENT_PLAN,
                "project", "doc", "1", RetrievalEvaluationCase.ExpectedOutcome.HIT,
                List.of(new RetrievalEvaluationCase.GoldDocument("gold.html", null, List.of("gold"))),
                List.of(new RetrievalEvaluationCase.GoldCode("project", "src/Gold.java", "goldMethod")),
                List.of("normal-recall"), "trace");
        ChunkRecord otherDocument = new ChunkRecord("doc-other", "doc", "1", "other.html",
                "parent-other", "other", "", "hash-other", 0, 0);
        ChunkRecord goldDocument = new ChunkRecord("doc-gold", "doc", "1", "gold.html",
                "parent-gold", "gold", "", "hash-gold", 1, 0);
        CodeChunk otherCode = new CodeChunk("code-other", "project", "commit", "src/Other.java",
                "method", "otherMethod", 1, 2, "other", "hash-other");
        CodeChunk goldCode = new CodeChunk("code-gold", "project", "commit", "src/Gold.java",
                "method", "goldMethod", 1, 2, "gold", "hash-gold");

        RetrievalEvaluationMatcher.CaseResult promoted = RetrievalEvaluationMatcher.evaluate(
                mixed, List.of(goldDocument), List.of(goldCode), 4, 3, 5,
                null, null, 1, List.of(), List.of(),
                new RetrievalEvaluationMatcher.EvaluationTrace(
                        true, List.of(otherDocument, goldDocument),
                        List.of(otherDocument, goldDocument), List.of(goldDocument, otherDocument),
                        true, List.of(otherCode, goldCode), List.of(goldCode, otherCode)));

        assertEquals(2, promoted.documentRawRank());
        assertEquals(2, promoted.documentRerankInputRank());
        assertEquals(1, promoted.documentRerankedRank());
        assertEquals(1, promoted.documentRank());
        assertEquals("PROMOTED", promoted.documentRankMovement());
        assertTrue(promoted.documentOrderChanged());
        assertEquals(2, promoted.codeRawRank());
        assertEquals(1, promoted.codeRankedRank());
        assertEquals(1, promoted.codeRank());
        assertEquals("PROMOTED", promoted.codeRankMovement());
        assertTrue(promoted.codeOrderChanged());
        assertTrue(promoted.failureAttributions().isEmpty());

        RetrievalEvaluationCase documentOnly = documentCase("candidate-miss", "gold.html", "gold");
        RetrievalEvaluationMatcher.CaseResult missed = RetrievalEvaluationMatcher.evaluate(
                documentOnly, List.of(), List.of(), 2, 0, 2,
                null, null, 1, List.of(), List.of(),
                new RetrievalEvaluationMatcher.EvaluationTrace(
                        true, List.of(otherDocument), List.of(otherDocument), List.of(otherDocument),
                        false, List.of(), List.of()));
        assertFalse(missed.success());
        assertEquals(List.of("DOCUMENT_CANDIDATE_RECALL_MISS"), missed.failureAttributions());
        assertEquals(1, RetrievalEvaluationReport.create("trace.jsonl", List.of(missed))
                .failureAttributions().get("DOCUMENT_CANDIDATE_RECALL_MISS"));
    }

    @Test
    void committedBaselineDefinesNonZeroCiRegressionThresholds() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("evaluation/retrieval-baseline-v0.7.json")) {
            assertTrue(input != null);
            String baseline = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(baseline.contains("\"documentRecallAt10\": 0.80"));
            assertTrue(baseline.contains("\"codeRecallAt10\": 0.75"));
            assertTrue(baseline.contains("\"mrrAt10\": 0.65"));
            assertTrue(baseline.contains("\"p95LatencyMs\": 5000"));
        }
    }

    @Test
    void documentV2RunnerFreezesLiveDependencyTimeoutsAndCalibrationScope() throws Exception {
        String runner = Files.readString(Path.of("scripts", "run-document-v2-eval.sh"));

        assertTrue(runner.contains("BRANCH_TIMEOUT_MS=\"30000\""));
        assertTrue(runner.contains("BGE_READ_TIMEOUT_MS=\"120000\""));
        assertTrue(runner.contains("RETRIEVAL_BRANCH_TIMEOUT_MS=\"${BRANCH_TIMEOUT_MS}\""));
        assertTrue(runner.contains("RETRIEVAL_EVAL_WARMUP_RUNS:-0"));
        assertTrue(runner.contains("RETRIEVAL_EVAL_REPETITIONS:-1"));
        assertTrue(runner.contains("RETRIEVAL_EVAL_MODE=\"0.8.2-document-v2\""));
        assertTrue(runner.contains("RETRIEVAL_EVAL_SETUP_SKIP_CODE=\"true\""));
        assertTrue(runner.contains("VERSION=\"document-v2-v2\""));
    }

    private static RetrievalEvaluationCase documentCase(String id, String filename, String fragment) {
        return new RetrievalEvaluationCase(id, id + " query",
                RetrievalEvaluationCase.RetrievalProfile.REQUIREMENT_REVIEW,
                "project", "doc", "1", RetrievalEvaluationCase.ExpectedOutcome.HIT,
                List.of(new RetrievalEvaluationCase.GoldDocument(filename, null, List.of(fragment))),
                List.of(), List.of("normal-recall"), "test");
    }

    private static void assertInvalid(String input, String... messages) {
        RetrievalEvaluationDataset.DatasetValidationException error = assertThrows(
                RetrievalEvaluationDataset.DatasetValidationException.class,
                () -> RetrievalEvaluationDataset.parse(new StringReader(input)));
        for (String message : messages) {
            assertTrue(error.getMessage().contains(message), error.getMessage());
        }
    }

    private static String validCase(String id) {
        return "{\"id\":\"" + id + "\",\"query\":\"真实问题\",\"profile\":\"DEVELOPMENT_PLAN\"," +
                "\"projectId\":\"project\",\"documentId\":\"doc\",\"version\":\"1\"," +
                "\"expectedOutcome\":\"HIT\",\"goldDocuments\":[{\"filename\":\"5.1/example.html\"," +
                "\"mustContain\":[\"证据\"]}],\"goldCode\":[],\"tags\":[],\"notes\":\"ok\"}";
    }

    private static String validV2Case(String id) {
        return "{\"id\":\"" + id + "\",\"query\":\"真实问题\",\"profile\":\"DEVELOPMENT_PLAN\","
                + "\"projectId\":\"project\",\"documentId\":\"doc\",\"version\":\"1\","
                + "\"expectedOutcome\":\"HIT\",\"goldDocuments\":[{\"filename\":\"5.1/example.html\","
                + "\"mustContain\":[\"证据\"],"
                + "\"evidenceId\":\"requirement:project:1:5.1/example.html:*:*\"}],"
                + "\"goldCode\":[],\"tags\":[],\"notes\":\"ok\",\"schemaVersion\":2,"
                + "\"queryType\":\"BUSINESS_SEMANTIC\",\"sourceCommit\":\"" + ENTERPRISE_COMMIT + "\","
                + "\"review\":{\"status\":\"APPROVED\",\"reviewer\":\"qa-team\","
                + "\"reviewedAt\":\"2026-08-17T10:00:00+08:00\"}}";
    }

    private static ChunkRecord chunk(String filename, int parentOrder, int childOrder,
                                     String parentText, String childText) {
        return new ChunkRecord("id", "doc", "1", filename, "parent", parentText, childText,
                "hash", parentOrder, childOrder);
    }

    private static CodeChunk code(String projectId, String filePath, String symbolName) {
        return new CodeChunk("id", projectId, "commit", filePath, "method", symbolName,
                1, 2, "text", "hash");
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) result.append(String.format("%02x", item));
        return result.toString();
    }
}
