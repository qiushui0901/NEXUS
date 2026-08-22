package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.MultiSourceSearchResponse;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MultiSourceSearchServiceTest {
    @TempDir Path tempDir;

    private MultiSourceSearchService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        MultiSourceKnowledgeStore store = new MultiSourceKnowledgeStore(
                tempDir.resolve("search.db").toString(), objectMapper);
        ParameterTableLoader loader = new ParameterTableLoader();
        DoubtClaimParser doubtParser = new DoubtClaimParser();
        var layout = loader.parseHeaders(List.of("模块", "参数", "值", "单位", "版本"));
        store.replaceSnapshot("fengshen", "5.1",
                loader.parse(layout, List.of(Map.of("0", "权限撤销", "1", "传播时间", "2", "5分钟", "3", "分钟", "4", "5.1")),
                        "fengshen", "5.1", "参数表.xlsx", "5.1参数"),
                List.of(doubtParser.parse(Map.of("问题", "权限撤销未确认", "状态", "OPEN"),
                        "fengshen", "5.1", "5.1存疑", 1)),
                List.of(), List.of());
        service = new MultiSourceSearchService(store,
                new KnowledgeQueryIntentClassifier(), new MultiSourceKnowledgeGate(),
                new SourceFilterStrategy(), new MultiSourceConflictAnalyzer());
    }

    @Test
    void chineseQueryWithoutSpacesIsTokenizedIntoBigrams() {
        MultiSourceSearchResponse response = service.search("fengshen", "5.1", "权限撤销传播时间是多少");

        assertThat(response.intent()).isEqualTo(KnowledgeQueryIntent.PARAMETER);
        assertThat(response.claims()).isNotEmpty();
        assertThat(response.claims().get(0).sourceType().name()).isEqualTo("PARAMETER_TABLE");
    }

    @Test
    void limitAndPageReturnSlicedResults() {
        MultiSourceSearchResponse page0 = service.search("fengshen", "5.1", "权限撤销 传播时间", null, 1, 0);
        MultiSourceSearchResponse page1 = service.search("fengshen", "5.1", "权限撤销 传播时间", null, 1, 1);

        assertThat(page0.claims()).hasSize(1);
        assertThat(page0.claims().get(0).claimId()).isNotEqualTo(
                page1.claims().isEmpty() ? "" : page1.claims().get(0).claimId());
    }

    @Test
    void consistencyIntentReturnsOpenDoubts() {
        MultiSourceSearchResponse response = service.search("fengshen", "5.1", "需求和测试是否一致", null, 20, 0);

        assertThat(response.intent()).isEqualTo(KnowledgeQueryIntent.CONSISTENCY);
        assertThat(response.doubts()).extracting(DoubtClaim::status)
                .contains(MultiSourceKnowledgeModels.DoubtStatus.OPEN);
    }

    @Test
    void globallyDisabledProjectReturnsDegradedEmptyResponse() {
        MultiSourceSearchService disabled = new MultiSourceSearchService(
                newStore(), new KnowledgeQueryIntentClassifier(), new MultiSourceKnowledgeGate(),
                new SourceFilterStrategy(), new MultiSourceConflictAnalyzer(), List.of(),
                new CrossSourceRelationExtractor(), query -> Optional.empty(),
                new MultiSourceKnowledgeProperties(false, false, null, Map.of()));

        MultiSourceSearchResponse response = disabled.search("fengshen", "5.1", "权限撤销传播时间是多少");

        assertThat(response.answerStatus().name()).isEqualTo("NO_RESULT");
        assertThat(response.claims()).isEmpty();
        assertThat(response.warnings()).containsExactly("MULTI_SOURCE_DISABLED");
    }

    @Test
    void perProjectSwitchCanDisableSingleProject() {
        MultiSourceSearchService perProject = new MultiSourceSearchService(
                newStore(), new KnowledgeQueryIntentClassifier(), new MultiSourceKnowledgeGate(),
                new SourceFilterStrategy(), new MultiSourceConflictAnalyzer(), List.of(),
                new CrossSourceRelationExtractor(), query -> Optional.empty(),
                new MultiSourceKnowledgeProperties(true, false, null, Map.of("fengshen", false)));

        MultiSourceSearchResponse response = perProject.search("fengshen", "5.1", "权限撤销传播时间是多少");

        assertThat(response.answerStatus().name()).isEqualTo("NO_RESULT");
        assertThat(response.warnings()).contains("MULTI_SOURCE_DISABLED");
    }

    @Test
    void llmFallbackRefinesGenericQueryWhenEnabled() {
        MultiSourceSearchService withFallback = new MultiSourceSearchService(
                newStore(), new KnowledgeQueryIntentClassifier(), new MultiSourceKnowledgeGate(),
                new SourceFilterStrategy(), new MultiSourceConflictAnalyzer(), List.of(),
                new CrossSourceRelationExtractor(), query -> Optional.of(KnowledgeQueryIntent.PARAMETER),
                new MultiSourceKnowledgeProperties(true, true, null, Map.of()));

        MultiSourceSearchResponse response = withFallback.search("fengshen", "5.1", "随便聊聊");

        assertThat(response.intent()).isEqualTo(KnowledgeQueryIntent.PARAMETER);
        assertThat(response.warnings()).contains("intent classified via LLM: PARAMETER");
    }

    @Test
    void llmFallbackIgnoredWhenDisabled() {
        MultiSourceSearchService withoutFallback = new MultiSourceSearchService(
                newStore(), new KnowledgeQueryIntentClassifier(), new MultiSourceKnowledgeGate(),
                new SourceFilterStrategy(), new MultiSourceConflictAnalyzer(), List.of(),
                new CrossSourceRelationExtractor(), query -> Optional.of(KnowledgeQueryIntent.PARAMETER),
                new MultiSourceKnowledgeProperties(true, false, null, Map.of()));

        MultiSourceSearchResponse response = withoutFallback.search("fengshen", "5.1", "随便聊聊");

        assertThat(response.intent()).isEqualTo(KnowledgeQueryIntent.GENERAL);
        assertThat(response.warnings()).doesNotContain("intent classified via LLM");
    }

    @Test
    void llmRelationConfirmationRejectsFalseRelationsWhenEnabled() {
        MultiSourceKnowledgeStore store = newStore();
        store.replaceProjectVersion("fengshen", "5.1");
        TestKnowledgeLoaders testLoaders = new TestKnowledgeLoaders(new ObjectMapper());
        store.saveTestCases("fengshen", "5.1", List.of(testLoaders.parseTestCase(
                "{\"testCaseId\":\"tc-1\",\"title\":\"取消订单\",\"expectedResult\":\"可取消\","
                        + "\"module\":\"订单\",\"coveredRequirementId\":\"订单-001\",\"framework\":\"JUnit\"}",
                "fengshen", "5.1", "OrderTest.java")));
        MultiSourceCandidateAdapter requirementAdapter = requirementAdapter();

        MultiSourceSearchService service = new MultiSourceSearchService(
                store, new KnowledgeQueryIntentClassifier(), new MultiSourceKnowledgeGate(),
                new SourceFilterStrategy(), new MultiSourceConflictAnalyzer(), List.of(requirementAdapter),
                new CrossSourceRelationExtractor(), query -> Optional.empty(),
                new MultiSourceKnowledgeProperties(true, false, null, Map.of(), true),
                (source, relationType, target, evidence) ->
                        new CrossSourceRelationConfirmer.Confirmation(false, "不相关"));

        MultiSourceSearchResponse response = service.search(
                "fengshen", "5.1", "取消订单", KnowledgeQueryIntent.VALIDATION, 20, 0);

        assertThat(response.relations()).isEmpty();
        assertThat(response.warnings()).contains("LLM 语义确认拒绝 1 条规则关系");
    }

    @Test
    void llmRelationConfirmationKeepsConfirmedRelationsWhenEnabled() {
        MultiSourceKnowledgeStore store = newStore();
        store.replaceProjectVersion("fengshen", "5.1");
        TestKnowledgeLoaders testLoaders = new TestKnowledgeLoaders(new ObjectMapper());
        store.saveTestCases("fengshen", "5.1", List.of(testLoaders.parseTestCase(
                "{\"testCaseId\":\"tc-1\",\"title\":\"取消订单\",\"expectedResult\":\"可取消\","
                        + "\"module\":\"订单\",\"coveredRequirementId\":\"订单-001\",\"framework\":\"JUnit\"}",
                "fengshen", "5.1", "OrderTest.java")));

        MultiSourceSearchService service = new MultiSourceSearchService(
                store, new KnowledgeQueryIntentClassifier(), new MultiSourceKnowledgeGate(),
                new SourceFilterStrategy(), new MultiSourceConflictAnalyzer(), List.of(requirementAdapter()),
                new CrossSourceRelationExtractor(), query -> Optional.empty(),
                new MultiSourceKnowledgeProperties(true, false, null, Map.of(), true),
                (source, relationType, target, evidence) ->
                        new CrossSourceRelationConfirmer.Confirmation(true, "匹配"));

        MultiSourceSearchResponse response = service.search(
                "fengshen", "5.1", "取消订单", KnowledgeQueryIntent.VALIDATION, 20, 0);

        assertThat(response.relations()).isNotEmpty();
        assertThat(response.relations().get(0).type().name()).isEqualTo("VERIFIES");
        assertThat(response.warnings()).doesNotContain("LLM 语义确认拒绝 1 条规则关系");
    }

    private MultiSourceCandidateAdapter requirementAdapter() {
        return new MultiSourceCandidateAdapter() {
            @Override
            public SourceType sourceType() {
                return SourceType.REQUIREMENT;
            }

            @Override
            public List<UnifiedKnowledgeClaim> load(String projectId, String version, String query) {
                return List.of(new UnifiedKnowledgeClaim("req:1", "fengshen", "5.1",
                        "fengshen|5.1|订单|订单-001", "订单-001", "允许取消", "允许", "TEXT", null,
                        SourceType.REQUIREMENT, Authority.PRIMARY,
                        MultiSourceKnowledgeModels.KnowledgeStatus.VERIFIED,
                        "5.1", null, "graph:s1#req:1", "订单"));
            }
        };
    }

    private MultiSourceKnowledgeStore newStore() {
        ObjectMapper objectMapper = new ObjectMapper();
        return new MultiSourceKnowledgeStore(tempDir.resolve(System.nanoTime() + "-search.db").toString(), objectMapper);
    }
}