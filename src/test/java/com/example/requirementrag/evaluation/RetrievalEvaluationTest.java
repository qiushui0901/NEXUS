package com.example.requirementrag.evaluation;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalEvaluationTest {

    @Test
    void loadsTenConfirmedGoldCases() {
        List<RetrievalEvaluationCase> cases = RetrievalEvaluationDataset.loadDefault();

        assertEquals(10, cases.size());
        assertEquals("growth-fund-red-dot", cases.getFirst().id());
        assertEquals("mount-attribute-scope", cases.getLast().id());
        assertEquals(1, cases.stream()
                .filter(value -> value.expectedOutcome() == RetrievalEvaluationCase.ExpectedOutcome.NO_RESULTS)
                .count());
    }

    @Test
    void keepsGrowthFundRequirementGoldSeparateFromGrowthDiscountCodeGold() {
        List<RetrievalEvaluationCase> cases = RetrievalEvaluationDataset.loadDefault();
        List<RetrievalEvaluationCase> growthFundCases = cases.stream()
                .filter(value -> value.tags().contains("growth-fund"))
                .toList();
        RetrievalEvaluationCase growthDiscount = cases.stream()
                .filter(value -> value.id().equals("growth-discount-purchase-code"))
                .findFirst().orElseThrow();

        assertFalse(growthFundCases.isEmpty());
        assertTrue(growthFundCases.stream().allMatch(value -> value.goldCode().isEmpty()));
        assertTrue(growthFundCases.stream().allMatch(value -> value.tags().contains("version-5.1")));
        assertTrue(growthDiscount.goldDocuments().isEmpty());
        assertTrue(growthDiscount.tags().contains("version-5.0"));
        assertTrue(growthDiscount.notes().contains("不是同一功能"));
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
        String input = validCase("blank-query").replace("真实问题", "   ");

        assertInvalid(input, "line 1", "query must not be blank");
    }

    @Test
    void rejectsHitWithoutGold() {
        String input = validCase("missing-gold")
                .replace("[{\"filename\":\"5.1/example.html\",\"mustContain\":[\"证据\"]}]", "[]");

        assertInvalid(input, "line 1", "HIT requires");
    }

    @Test
    void rejectsNoResultsWithGold() {
        String input = validCase("bad-no-results").replace("\"HIT\"", "\"NO_RESULTS\"");

        assertInvalid(input, "line 1", "NO_RESULTS must not contain Gold");
    }

    @Test
    void rejectsUnstablePointAndVectorIdentifiers() {
        String input = validCase("point-id").replace("\"notes\":\"ok\"", "\"pointId\":\"123\",\"notes\":\"ok\"");

        assertInvalid(input, "line 1", "unstable point/vector identifiers");
    }

    @Test
    void rejectsInvalidCodePath() {
        String input = """
                {"id":"absolute-code-path","query":"真实问题","profile":"CODE_SEARCH","projectId":"project",\
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
        RetrievalEvaluationCase noResults = RetrievalEvaluationDataset.loadDefault().stream()
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
    void reportCalculatesRecallMrrNoResultAndLatency() {
        RetrievalEvaluationCase documentCase = RetrievalEvaluationDataset.loadDefault().getFirst();
        RetrievalEvaluationCase noResults = RetrievalEvaluationDataset.loadDefault().stream()
                .filter(value -> value.expectedOutcome() == RetrievalEvaluationCase.ExpectedOutcome.NO_RESULTS)
                .findFirst().orElseThrow();
        RetrievalEvaluationMatcher.CaseResult hit = RetrievalEvaluationMatcher.evaluate(
                documentCase,
                List.of(chunk("other.html", 0, 0, "", ""),
                        chunk("5.1/福利-成长基金.html", 1, 0,
                                "与是否已购买无关", "未购买但已达标同样显示红点")),
                List.of(), 3, 0, 3);
        RetrievalEvaluationMatcher.CaseResult miss = RetrievalEvaluationMatcher.evaluate(
                noResults, List.of(chunk("unrelated.html", 0, 0, "", "")), List.of(), 7, 0, 7);

        RetrievalEvaluationReport report = RetrievalEvaluationReport.create("test.jsonl", List.of(hit, miss));

        assertEquals(0.5, report.summary().mrrAt10());
        assertEquals(1.0, report.summary().documentRecallAt10());
        assertEquals(0.0, report.summary().noResultAccuracy());
        assertEquals(1, report.summary().failedCases());
        assertEquals(3, report.summary().p50LatencyMs());
        assertEquals(7, report.summary().p95LatencyMs());
        assertTrue(report.markdown().contains("growth-fund-red-dot"));
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
}
