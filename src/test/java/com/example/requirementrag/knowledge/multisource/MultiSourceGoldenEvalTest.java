package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.MultiSourceSearchResponse;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterClaim;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MultiSourceGoldenEvalTest {
    @TempDir Path tempDir;

    private ObjectMapper objectMapper = new ObjectMapper();
    private MultiSourceSearchService searchService;

    @BeforeEach
    void setUp() {
        MultiSourceKnowledgeStore store = new MultiSourceKnowledgeStore(
                tempDir.resolve("golden.db").toString(), objectMapper);
        ParameterTableLoader parameterLoader = new ParameterTableLoader();
        DoubtClaimParser doubtParser = new DoubtClaimParser();
        TestKnowledgeLoaders testLoaders = new TestKnowledgeLoaders(objectMapper);

        var layout = parameterLoader.parseHeaders(List.of("模块", "参数", "值", "单位", "版本"));
        List<ParameterClaim> parameters = parameterLoader.parse(layout,
                List.of(Map.of("0", "权限撤销", "1", "传播时间", "2", "5分钟", "3", "分钟", "4", "5.1")),
                "fengshen", "5.1", "参数表.xlsx", "5.1参数");
        store.replaceProjectVersion("fengshen", "5.1");
        store.saveParameters("fengshen", "5.1", parameters);
        store.saveDoubts("fengshen", "5.1", List.of(doubtParser.parse(
                Map.of("问题", "权限撤销未确认", "状态", "OPEN"), "fengshen", "5.1", "5.1存疑", 1)));
        store.saveTestCases("fengshen", "5.1", List.of(testLoaders.parseTestCase(
                "{\"testCaseId\":\"tc-001\",\"title\":\"取消订单测试覆盖\",\"expectedResult\":\"库存扣减成功\",\"coveredRequirementId\":\"订单-001\",\"framework\":\"JUnit\"}",
                "fengshen", "5.1", "OrderTest.java")));
        store.saveTestResults("fengshen", "5.1", List.of(testLoaders.parseTestResult(
                "{\"testCaseId\":\"tc-001\",\"testRunId\":\"run-1\",\"executionStatus\":\"PASSED\",\"environment\":\"ci\",\"executedAt\":\"2026-08-22\"}",
                "fengshen", "5.1")));

        searchService = new MultiSourceSearchService(store,
                new KnowledgeQueryIntentClassifier(), new MultiSourceKnowledgeGate(),
                new SourceFilterStrategy(), new MultiSourceConflictAnalyzer());
    }

    @Test
    void goldenDatasetMeetsExpectations() throws Exception {
        List<JsonNode> cases = readGoldenCases();
        assertThat(cases).hasSizeGreaterThanOrEqualTo(4);
        for (JsonNode node : cases) {
            String query = node.get("query").asText();
            MultiSourceSearchResponse response = searchService.search("fengshen", "5.1", query);
            String expectedIntent = node.has("intent") ? node.get("intent").asText() : null;
            if (expectedIntent != null) {
                assertThat(response.intent().name()).as("intent for query [%s]", query).isEqualTo(expectedIntent);
            }
            assertThat(response.answerStatus().name()).as("status for query [%s]", query)
                    .isEqualTo(node.get("status").asText());
            if (node.has("minSources")) {
                for (JsonNode source : node.get("minSources")) {
                    assertThat(response.claims()).as("source for query [%s]", query)
                            .anyMatch(claim -> claim.sourceType().name().equals(source.asText()));
                }
            }
            if (node.has("minDoubts")) {
                assertThat(response.doubts()).as("doubts for query [%s]", query)
                        .hasSizeGreaterThanOrEqualTo(node.get("minDoubts").asInt());
            }
        }
    }

    private List<JsonNode> readGoldenCases() throws Exception {
        List<JsonNode> result = new ArrayList<>();
        try (InputStream input = getClass().getResourceAsStream("/evaluation/multi-source-golden.jsonl")) {
            assertThat(input).as("golden dataset resource").isNotNull();
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                if (line.isBlank()) continue;
                result.add(objectMapper.readTree(line));
            }
        }
        return result;
    }
}