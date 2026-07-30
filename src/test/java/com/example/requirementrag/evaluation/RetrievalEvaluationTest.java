package com.example.requirementrag.evaluation;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.RagStageDiagnostic;
import com.example.requirementrag.model.RagWarning;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
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
            "1ff996579588bfc5b859b5a483427c255325265b211e452af5eaff6471a61b18";

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
    void documentGoldCanAccumulateEvidenceAcrossMatchingChunks() {
        RetrievalEvaluationCase.GoldDocument gold = new RetrievalEvaluationCase.GoldDocument(
                "5.1/福利-成长基金.html", null,
                List.of("一键领取", "后端需幂等", "POST /growth-fund/claim-all"));
        List<ChunkRecord> candidates = List.of(
                chunk("other.html", 0, 0, "无关", "无关"),
                chunk("5.1/福利-成长基金.html", 2, 0, "一键领取", "后端需幂等"),
                chunk("5.1/福利-成长基金.html", 3, 0, "接口", "POST /growth-fund/claim-all"));

        assertEquals(3, RetrievalEvaluationMatcher.firstDocumentRank(List.of(gold), candidates, 10));
    }

    @Test
    void documentParentOrderMustMatchWhenSpecified() {
        RetrievalEvaluationCase.GoldDocument gold = new RetrievalEvaluationCase.GoldDocument(
                "5.1/福利-成长基金.html", 1, List.of("显示红点"));
        List<ChunkRecord> candidates = List.of(
                chunk("5.1/福利-成长基金.html", 0, 0, "显示红点", ""),
                chunk("5.1/福利-成长基金.html", 1, 0, "显示红点", ""));

        assertEquals(2, RetrievalEvaluationMatcher.firstDocumentRank(List.of(gold), candidates, 10));
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
                        new RagStageDiagnostic("bge.rerank", RagOutcomeStatus.NO_RESULTS, 0, 0)));

        assertEquals(2, result.repetition());
        assertEquals(2, result.bgeCalls());
        assertEquals(1, result.bgeSuccesses());
        assertEquals(1, result.bgeDegradations());
        assertEquals(1, result.bgeNoCandidateSkips());
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
        assertEquals(1, calibration.summary().infrastructureFailureCases());
        assertTrue(calibration.markdown().contains("1.500/2"));
        assertFalse(calibration.markdown().contains("2.000/2"));
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
