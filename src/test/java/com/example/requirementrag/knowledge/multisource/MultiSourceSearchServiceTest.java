package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.MultiSourceSearchResponse;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import com.example.requirementrag.knowledge.multisource.vector.ClaimVectorCandidateAdapter;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorFusion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultiSourceSearchServiceTest {
    @TempDir Path tempDir;

    private MultiSourceSearchService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        MultiSourceKnowledgeStore store = new MultiSourceKnowledgeStore(
                tempDir.resolve("search.db").toString(), objectMapper);
        seedParameterStore(store);
        service = new MultiSourceSearchService(store,
                new KnowledgeQueryIntentClassifier(), new MultiSourceKnowledgeGate(),
                new SourceFilterStrategy(), new MultiSourceConflictAnalyzer());
    }

    private void seedParameterStore(MultiSourceKnowledgeStore store) {
        ParameterTableLoader loader = new ParameterTableLoader();
        DoubtClaimParser doubtParser = new DoubtClaimParser();
        var layout = loader.parseHeaders(List.of("模块", "参数", "值", "单位", "版本"));
        store.replaceSnapshot("fengshen", "5.1",
                loader.parse(layout, List.of(Map.of("0", "权限撤销", "1", "传播时间", "2", "5分钟", "3", "分钟", "4", "5.1")),
                        "fengshen", "5.1", "参数表.xlsx", "5.1参数"),
                List.of(doubtParser.parse(Map.of("问题", "权限撤销未确认", "状态", "OPEN"),
                        "fengshen", "5.1", "5.1存疑", 1)),
                List.of(), List.of());
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
        assertThat(page0.total()).isEqualTo(1);
        assertThat(page0.page()).isEqualTo(0);
        assertThat(page0.limit()).isEqualTo(1);
        assertThat(page0.hasMore()).isFalse();
        assertThat(page1.total()).isEqualTo(1);
        assertThat(page1.page()).isEqualTo(1);
        assertThat(page1.limit()).isEqualTo(1);
        assertThat(page1.hasMore()).isFalse();
    }

    @Test
    void paginationMetadataExposesHasMoreAndOutOfRangePages() {
        MultiSourceKnowledgeStore store = newStore();
        ParameterTableLoader loader = new ParameterTableLoader();
        var layout = loader.parseHeaders(List.of("模块", "参数", "值", "单位", "版本"));
        store.replaceProjectVersion("fengshen", "5.1");
        store.saveParameters("fengshen", "5.1", loader.parse(layout, List.of(
                Map.of("0", "权限撤销", "1", "传播时间", "2", "5分钟", "3", "分钟", "4", "5.1"),
                Map.of("0", "权限撤销", "1", "重试次数", "2", "3次", "3", "次", "4", "5.1")),
                "fengshen", "5.1", "参数表.xlsx", "5.1参数"));
        MultiSourceSearchService paged = new MultiSourceSearchService(store,
                new KnowledgeQueryIntentClassifier(), new MultiSourceKnowledgeGate(),
                new SourceFilterStrategy(), new MultiSourceConflictAnalyzer());

        MultiSourceSearchResponse first = paged.search("fengshen", "5.1", "权限撤销", null, 1, 0);
        MultiSourceSearchResponse second = paged.search("fengshen", "5.1", "权限撤销", null, 1, 1);
        MultiSourceSearchResponse outOfRange = paged.search("fengshen", "5.1", "权限撤销", null, 1, 99);

        assertThat(first.total()).isEqualTo(2);
        assertThat(first.page()).isEqualTo(0);
        assertThat(first.limit()).isEqualTo(1);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.hasMore()).isFalse();
        assertThat(outOfRange.claims()).isEmpty();
        assertThat(outOfRange.page()).isEqualTo(99);
        assertThat(outOfRange.hasMore()).isFalse();
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
    void relationsReadFromStoreAreBoundedToCurrentPage() throws Exception {
        MultiSourceKnowledgeStore store = newStore();
        store.replaceProjectVersion("fengshen", "5.1");
        TestKnowledgeLoaders testLoaders = new TestKnowledgeLoaders(new ObjectMapper());
        List<MultiSourceKnowledgeModels.TestCaseClaim> testCases = List.of(
                testLoaders.parseTestCase(
                        "{\"testCaseId\":\"tc-1\",\"title\":\"取消订单A\",\"expectedResult\":\"可取消\","
                                + "\"module\":\"订单\",\"coveredRequirementId\":\"订单-001\",\"framework\":\"JUnit\"}",
                        "fengshen", "5.1", "OrderTest.java"),
                testLoaders.parseTestCase(
                        "{\"testCaseId\":\"tc-2\",\"title\":\"取消订单B\",\"expectedResult\":\"可取消\","
                                + "\"module\":\"订单\",\"coveredRequirementId\":\"订单-001\",\"framework\":\"JUnit\"}",
                        "fengshen", "5.1", "OrderTest.java"));
        store.saveTestCases("fengshen", "5.1", testCases);
        seedOfflineRelations(store, testCases);

        MultiSourceSearchService service = new MultiSourceSearchService(
                store, new KnowledgeQueryIntentClassifier(), new MultiSourceKnowledgeGate(),
                new SourceFilterStrategy(), new MultiSourceConflictAnalyzer(), List.of(requirementAdapter()),
                new CrossSourceRelationExtractor());

        // 单条命中页：只返回页内测试用例的一跳关系（需求在页外也可作为一跳目标返回）。
        MultiSourceSearchResponse pageOne = service.search(
                "fengshen", "5.1", "取消订单", KnowledgeQueryIntent.VALIDATION, 1, 0);
        assertThat(pageOne.claims()).hasSize(1);
        assertThat(pageOne.claims().get(0).sourceType().name()).isEqualTo("TEST_CASE");
        assertThat(pageOne.relations()).hasSize(1);

        // 全量页：两条测试用例各返回一跳关系。
        MultiSourceSearchResponse fullPage = service.search(
                "fengshen", "5.1", "取消订单", KnowledgeQueryIntent.VALIDATION, 20, 0);
        assertThat(fullPage.relations()).hasSize(2);
        assertThat(fullPage.relations()).allSatisfy(relation ->
                org.assertj.core.api.Assertions.assertThat(relation.type().name()).isEqualTo("VERIFIES"));
    }

    /** 预置离线关系：建立 catalog 版本/Evidence、同步统一 Claim，并写入 knowledge_relation。 */
    private void seedOfflineRelations(MultiSourceKnowledgeStore store,
                                      List<MultiSourceKnowledgeModels.TestCaseClaim> testCases) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        store.registerDocument(new KnowledgeCatalogModels.KnowledgeDocument(
                "doc-1", "fengshen", SourceType.REQUIREMENT, "combat-requirement",
                "combat.docx", "file:///data/combat.docx", Authority.PRIMARY, null));
        store.upsertDocumentVersion(new KnowledgeCatalogModels.KnowledgeDocumentVersion(
                "dv-1", "doc-1", "fengshen", "5.1", "hash-1", "v1", "v1", null, "DRAFT", null, null));
        String evidenceId = KnowledgeEvidenceIdGenerator.generate("fengshen", "dv-1", "combat.md#3.2", "excerpt-1");
        store.saveEvidence(new KnowledgeCatalogModels.KnowledgeEvidence(
                evidenceId, "dv-1", "fengshen", SourceType.REQUIREMENT,
                "combat.md#3.2", "excerpt", "excerpt-1", null, null, null, null, null, null, null, null, null));

        Map<String, String> evidenceMap = new java.util.LinkedHashMap<>();
        for (MultiSourceKnowledgeModels.TestCaseClaim testCase : testCases) {
            evidenceMap.put(testCase.claimId(), evidenceId);
        }
        store.syncSnapshotClaims("fengshen", "5.1", "dv-1", evidenceMap);

        // 需求 Claim 直接落主库
        store.saveClaim(new KnowledgeCatalogModels.KnowledgeClaimRecord(
                "req:1", "fengshen", "dv-1", SourceType.REQUIREMENT, Authority.PRIMARY,
                KnowledgeFactKeyGenerator.generate("fengshen", "5.1", "订单", "订单-001", "允许取消"),
                "订单-001", "允许取消", "允许", "TEXT", null, "VERIFIED",
                null, null, null, "RULE", null, null, null));

        for (MultiSourceKnowledgeModels.TestCaseClaim testCase : testCases) {
            store.saveRelation(new KnowledgeCatalogModels.KnowledgeRelation(
                    "rel:verifies:" + testCase.claimId() + ":req:1",
                    "fengshen", "5.1", testCase.claimId(), "req:1",
                    "VERIFIES", "RULE_PROPOSED", null, evidenceId,
                    "RULE", null, null, null, null));
        }
    }

    @Test
    void searchTextIsNeverSerializedIntoHttpJson() throws Exception {
        // searchText 是内部评分字段（可能回退到 rawText，放大响应体），必须被 @JsonIgnore 排除在
        // HTTP 响应之外——前端不使用它，泄露还会显著放大多源检索响应体。
        UnifiedKnowledgeClaim claim = new UnifiedKnowledgeClaim(
                "req:1", "fengshen", "5.1", "fengshen|5.1|订单|订单-001",
                "订单-001", "允许取消", "允许", "TEXT", null,
                SourceType.REQUIREMENT, Authority.PRIMARY,
                MultiSourceKnowledgeModels.KnowledgeStatus.VERIFIED,
                "5.1", null, "graph:s1#req:1", "订单",
                "这是一段很长的语义检索文本，不应出现在 HTTP 响应中");

        String json = new ObjectMapper().writeValueAsString(claim);

        // searchText 及其内容不得出现在序列化 JSON 中；结构化字段仍正常输出。
        assertThat(json).doesNotContain("\"searchText\"");
        assertThat(json).doesNotContain("语义检索文本");
        assertThat(json).contains("订单-001");
        assertThat(json).contains("graph:s1#req:1");
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

    @Test
    void searchResponseCarriesActualSemanticBuildIdsReadDuringRetrieval() {
        // 高（Review #1）：检索响应必须回传本次实际读取的语义构建代际 ids，而非前端另行查询的状态——
        // 否则状态返回 b1、检索执行前发布 b2 时，评测记录绑定 b1 而检索读取 b2。
        // 这里模拟语义适配器在同一查询快照中读到 build ids，验证响应携带它们。
        MultiSourceCandidateAdapter semantic = new MultiSourceCandidateAdapter() {
            @Override
            public SourceType sourceType() {
                return SourceType.REQUIREMENT_SEMANTIC;
            }

            @Override
            public List<UnifiedKnowledgeClaim> load(String projectId, String version, String query) {
                return List.of();
            }

            @Override
            public MultiSourceCandidateAdapter.CandidateLoad loadDetailed(String projectId, String version,
                                                                         String query, KnowledgeQueryIntent intent) {
                UnifiedKnowledgeClaim claim = new UnifiedKnowledgeClaim("sem:1", "fengshen", "5.1",
                        "fengshen|5.1|订单|订单-001", "订单-001", "允许取消", "允许", "TEXT", null,
                        SourceType.REQUIREMENT, Authority.PRIMARY,
                        MultiSourceKnowledgeModels.KnowledgeStatus.SUPPORTED,
                        "5.1", null, "graph:s1#req:1", "订单");
                return new MultiSourceCandidateAdapter.CandidateLoad(List.of(claim), List.of(),
                        List.of("build-2"));
            }
        };
        MultiSourceKnowledgeStore localStore = newStore();
        MultiSourceSearchService withSemantic = new MultiSourceSearchService(localStore,
                new KnowledgeQueryIntentClassifier(), new MultiSourceKnowledgeGate(),
                new SourceFilterStrategy(), new MultiSourceConflictAnalyzer(),
                List.of(semantic), new CrossSourceRelationExtractor());

        MultiSourceSearchResponse response =
                withSemantic.search("fengshen", "5.1", "订单", KnowledgeQueryIntent.VALIDATION);

        // 检索实际读取的代际 ids 出现在响应中，供前端评测上下文绑定。
        assertThat(response.semanticBuildIds()).containsExactly("build-2");
        // 语义源确实参与了检索（VALIDATION 意图含 REQUIREMENT_SEMANTIC 且适配器存在）。
        assertThat(response.semanticSourceAttempted()).isTrue();
    }

    @Test
    void searchResponseBuildIdsEmptyWhenNoSemanticCandidateSource() {
        // 没有语义候选适配器时响应 build ids 为空——前端据此判定无代际可绑定（fail-closed）。
        MultiSourceSearchResponse response = service.search("fengshen", "5.1", "订单",
                KnowledgeQueryIntent.VALIDATION);
        assertThat(response.semanticBuildIds()).isEmpty();
        // 无语义适配器 → 语义源未参与。
        assertThat(response.semanticSourceAttempted()).isFalse();
    }

    @Test
    void searchResponseSemanticSourceNotAttemptedForDoubtIntent() {
        // 高（Review #1）：DOUBT 意图排除 REQUIREMENT_SEMANTIC → 语义源未参与，
        // semanticSourceAttempted=false 且 buildIds 为空——前端据此 fail-closed，不回退到全局 active IDs。
        MultiSourceCandidateAdapter semantic = new MultiSourceCandidateAdapter() {
            @Override
            public SourceType sourceType() {
                return SourceType.REQUIREMENT_SEMANTIC;
            }

            @Override
            public List<UnifiedKnowledgeClaim> load(String projectId, String version, String query) {
                return List.of();
            }

            @Override
            public MultiSourceCandidateAdapter.CandidateLoad loadDetailed(String projectId, String version,
                                                                         String query, KnowledgeQueryIntent intent) {
                return new MultiSourceCandidateAdapter.CandidateLoad(List.of(), List.of(),
                        List.of("build-should-not-appear"));
            }
        };
        MultiSourceKnowledgeStore localStore = newStore();
        MultiSourceSearchService withSemantic = new MultiSourceSearchService(localStore,
                new KnowledgeQueryIntentClassifier(), new MultiSourceKnowledgeGate(),
                new SourceFilterStrategy(), new MultiSourceConflictAnalyzer(),
                List.of(semantic), new CrossSourceRelationExtractor());

        MultiSourceSearchResponse response =
                withSemantic.search("fengshen", "5.1", "订单", KnowledgeQueryIntent.DOUBT);

        // DOUBT 排除语义源 → 未参与 → 空 buildIds + semanticSourceAttempted=false。
        assertThat(response.semanticBuildIds()).isEmpty();
        assertThat(response.semanticSourceAttempted()).isFalse();
    }

    @Test
    void fusionPathFailureFallsBackToStructuredCandidates() {
        // 中（vaxr M1）：向量/融合/影子段不在 per-source 故障保护内——任何未捕获异常都曾让
        // 整个检索 500。修复后：融合路径故障降级为结构化候选 + MULTI_SOURCE_CANDIDATE_LOAD_FAILED:CLAIM_VECTOR 警告。
        MultiSourceKnowledgeStore localStore = newStore();
        seedParameterStore(localStore);
        ClaimVectorCandidateAdapter throwingAdapter = mock(ClaimVectorCandidateAdapter.class);
        when(throwingAdapter.sourceType()).thenReturn(SourceType.CLAIM_VECTOR);
        when(throwingAdapter.loadScored(anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("qdrant unavailable"));
        KnowledgeClaimVectorFusion fusion = mock(KnowledgeClaimVectorFusion.class);

        MultiSourceSearchService fused = new MultiSourceSearchService(localStore,
                new KnowledgeQueryIntentClassifier(), new MultiSourceKnowledgeGate(),
                new SourceFilterStrategy(), new MultiSourceConflictAnalyzer(),
                List.of(), new CrossSourceRelationExtractor(), null,
                MultiSourceKnowledgeProperties.enabledDefault(), null, fusion, null, throwingAdapter);

        // PARAMETER 意图含 CLAIM_VECTOR 与 PARAMETER_TABLE → 触发融合路径（负载段抛异常）
        // 且结构化参数候选仍在。
        MultiSourceSearchResponse response = fused.search("fengshen", "5.1", "权限撤销 传播时间",
                KnowledgeQueryIntent.PARAMETER);

        // 结构化候选不受影响，检索不 500。
        assertThat(response.claims()).isNotEmpty();
        // 融合故障以稳定告警码暴露，而非伪装成无向量结果。
        assertThat(response.warnings()).contains("MULTI_SOURCE_CANDIDATE_LOAD_FAILED:CLAIM_VECTOR");
    }

    private MultiSourceKnowledgeStore newStore() {
        ObjectMapper objectMapper = new ObjectMapper();
        return new MultiSourceKnowledgeStore(tempDir.resolve(System.nanoTime() + "-search.db").toString(), objectMapper);
    }
}