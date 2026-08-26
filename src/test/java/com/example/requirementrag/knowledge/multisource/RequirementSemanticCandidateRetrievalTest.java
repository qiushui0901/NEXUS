package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.AnswerStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.ClaimStatus;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.ExtractionStatus;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationRecord;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationResult;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildRecord;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildStatus;
import com.example.requirementrag.requirement.semantic.RequirementSemanticProperties;
import com.example.requirementrag.requirement.semantic.SQLiteRequirementSemanticStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语义候选进入多源检索的端到端验证（真实 SQLite 存储 + 适配器 + 检索服务，不依赖 Spring 上下文）：
 * 候选可见性、与参数事实的冲突治理、NORMATIVE 门禁、结论状态与来源优先级。
 */
class RequirementSemanticCandidateRetrievalTest {
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

        RequirementSemanticProperties properties = new RequirementSemanticProperties(
                true, true, false, false,
                tempDir.resolve("semantic.db").toString(), "test-model",
                "requirement-semantic-v1", "v1", 12_000, 30, 30, 30, 30, 20, 30, 0,
                1_000, 1_800, 1_000_000, 400, true, 5_000);
        SQLiteRequirementSemanticStore semanticStore = new SQLiteRequirementSemanticStore(objectMapper, properties);
        // 语义候选：与参数表同一 subject|predicate（权限撤销|传播时间），值不同（3分钟 vs 5分钟）。
        SemanticAnnotationRecord a1 = annotation("a-1", "rev-1", new SemanticAnnotationResult(
                List.of(),
                List.of(new RequirementSemanticModels.SemanticCondition("权限撤销", "传播时间",
                        "EQ", "3分钟", "分钟", "DURATION", "", "EXPLICIT", "权限撤销的传播时间为3分钟")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), true));
        // 只有语义侧覆盖的事实：用于验证语义候选单独出现时结论只能是 SUPPORTED。
        SemanticAnnotationRecord a2 = annotation("a-2", "rev-1", new SemanticAnnotationResult(
                List.of(new RequirementSemanticModels.SemanticEntity("成长基金", "CURRENCY",
                        List.of(), "EXPLICIT", "玩家达到30级后开放成长基金")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), true));
        saveActiveAnnotations(semanticStore, "rev-1", List.of(a1, a2));

        RequirementSemanticCandidateAdapter semanticAdapter =
                new RequirementSemanticCandidateAdapter(semanticStore, properties);
        service = new MultiSourceSearchService(store,
                new KnowledgeQueryIntentClassifier(), new MultiSourceKnowledgeGate(),
                new SourceFilterStrategy(), new MultiSourceConflictAnalyzer(),
                List.of(semanticAdapter, new FakeCodeAdapter()), new CrossSourceRelationExtractor());
    }

    @Test
    void semanticCandidatesEnterMultiSourceSearchResults() {
        var response = service.search("fengshen", "5.1", "权限撤销 传播时间");

        assertThat(response.claims()).isNotEmpty();
        assertThat(response.claims())
                .anySatisfy(claim -> assertThat(claim.sourceType()).isEqualTo(SourceType.REQUIREMENT_SEMANTIC));
        UnifiedKnowledgeClaim semantic = response.claims().stream()
                .filter(claim -> claim.sourceType() == SourceType.REQUIREMENT_SEMANTIC)
                .findFirst().orElseThrow();
        assertThat(semantic.subject()).isEqualTo("权限撤销");
        assertThat(semantic.predicate()).isEqualTo("传播时间");
        assertThat(semantic.value()).isEqualTo("3分钟");
        assertThat(semantic.authority()).isEqualTo(Authority.SECONDARY);
        assertThat(semantic.status()).isEqualTo(KnowledgeStatus.EXTRACTED);
    }

    @Test
    void semanticValueMismatchingParameterTableIsReportedAsConflict() {
        var response = service.search("fengshen", "5.1", "权限撤销 传播时间");

        assertThat(response.conflicts()).anySatisfy(conflict ->
                assertThat(conflict).contains("需求语义候选(3分钟) 与参数表(5分钟)不一致"));
        // 参数表是 PRIMARY 来源，冲突出现时结论状态为 CONFLICTED 而非确认。
        assertThat(response.answerStatus()).isEqualTo(AnswerStatus.CONFLICTED);
    }

    @Test
    void normativeIntentHidesSemanticCandidatesByDefault() {
        var response = service.search("fengshen", "5.1", "权限撤销 传播时间", KnowledgeQueryIntent.NORMATIVE);

        assertThat(response.claims()).isNotEmpty();
        assertThat(response.claims())
                .noneMatch(claim -> claim.sourceType() == SourceType.REQUIREMENT_SEMANTIC);
        assertThat(response.claims())
                .anyMatch(claim -> claim.sourceType() == SourceType.PARAMETER_TABLE);
    }

    @Test
    void semanticOnlyResultsNeverBecomeConfirmed() {
        var response = service.search("fengshen", "5.1", "成长基金");

        assertThat(response.claims()).isNotEmpty();
        assertThat(response.claims())
                .allMatch(claim -> claim.sourceType() == SourceType.REQUIREMENT_SEMANTIC);
        assertThat(response.answerStatus()).isEqualTo(AnswerStatus.SUPPORTED);
    }

    @Test
    void codeFactsOutrankSemanticCandidatesOnEqualScores() {
        // CONSISTENCY 意图同时允许 CODE 与 REQUIREMENT_SEMANTIC；同分时按来源稳定排序，
        // CODE / PARAMETER_TABLE 必须排在 REQUIREMENT_SEMANTIC 之前（对齐层优先于语义候选）。
        var response = service.search("fengshen", "5.1", "权限撤销 传播时间", KnowledgeQueryIntent.CONSISTENCY);

        List<SourceType> order = response.claims().stream()
                .map(UnifiedKnowledgeClaim::sourceType).distinct().toList();
        assertThat(order).containsSubsequence(SourceType.CODE, SourceType.PARAMETER_TABLE,
                SourceType.REQUIREMENT_SEMANTIC);
    }

    /** 与语义候选同 subject|predicate 的 CODE 事实：验证来源优先级，不引入代码检索基础设施。 */
    private static final class FakeCodeAdapter implements MultiSourceCandidateAdapter {
        @Override
        public SourceType sourceType() {
            return SourceType.CODE;
        }

        @Override
        public List<UnifiedKnowledgeClaim> load(String projectId, String version, String query) {
            return List.of(new UnifiedKnowledgeClaim("code-1", projectId, version,
                    (projectId + "|" + version + "|权限撤销|传播时间").toLowerCase(java.util.Locale.ROOT),
                    "权限撤销", "传播时间", "5分钟", "TEXT", null,
                    SourceType.CODE, Authority.PRIMARY, KnowledgeStatus.SUPPORTED,
                    version, null, "code:Config.java#L10", "权限撤销"));
        }
    }

    @Test
    void candidateLoadFailureReturnsStableWarningWithoutExceptionDetails() {
        // 适配器抛出的异常可能携带内部信息（路径/SQL/provider URL）：对外 warning 只允许稳定码。
        MultiSourceKnowledgeStore store = new MultiSourceKnowledgeStore(
                tempDir.resolve("warn.db").toString(), new ObjectMapper());
        MultiSourceCandidateAdapter failing = new MultiSourceCandidateAdapter() {
            @Override
            public SourceType sourceType() {
                return SourceType.REQUIREMENT_SEMANTIC;
            }

            @Override
            public List<UnifiedKnowledgeClaim> load(String projectId, String version, String query) {
                throw new IllegalStateException("jdbc:sqlite:/secret/path database is locked");
            }
        };
        MultiSourceSearchService warnService = new MultiSourceSearchService(store,
                new KnowledgeQueryIntentClassifier(), new MultiSourceKnowledgeGate(),
                new SourceFilterStrategy(), new MultiSourceConflictAnalyzer(),
                List.of(failing), new CrossSourceRelationExtractor());

        var response = warnService.search("fengshen", "5.1", "权限撤销传播时间是多少");

        assertThat(response.warnings())
                .contains("MULTI_SOURCE_CANDIDATE_LOAD_FAILED:REQUIREMENT_SEMANTIC");
        assertThat(String.join(";", response.warnings()))
                .doesNotContain("/secret/path")
                .doesNotContain("jdbc")
                .doesNotContain("locked");
    }

    @Test
    void truncationWarningFromAdapterEntersSearchResponse() {
        // 适配器级非致命警告（候选截断）必须进入响应 warnings，调用方能感知结果不完整。
        MultiSourceKnowledgeStore store = new MultiSourceKnowledgeStore(
                tempDir.resolve("truncation.db").toString(), new ObjectMapper());
        MultiSourceCandidateAdapter truncating = new MultiSourceCandidateAdapter() {
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
                                                                           String query,
                                                                           KnowledgeQueryIntent intent) {
                return new MultiSourceCandidateAdapter.CandidateLoad(List.of(),
                        List.of("SEMANTIC_CANDIDATE_TRUNCATED"));
            }
        };
        MultiSourceSearchService truncationService = new MultiSourceSearchService(store,
                new KnowledgeQueryIntentClassifier(), new MultiSourceKnowledgeGate(),
                new SourceFilterStrategy(), new MultiSourceConflictAnalyzer(),
                List.of(truncating), new CrossSourceRelationExtractor());

        var response = truncationService.search("fengshen", "5.1", "权限撤销传播时间是多少");

        assertThat(response.warnings()).contains("SEMANTIC_CANDIDATE_TRUNCATED");
    }

    @Test
    void conflictStatusStableAcrossPagesWithOutsidePageFlag() {
        // 冲突双方（参数表 5分钟 vs 语义 3分钟 + CODE 5分钟）分散在不同页：
        // 状态按整个候选集计算——两页结论一致，翻页不改变事实结论；
        // 单条 Claim 的页面没有冲突双方详情，conflicts 为空但 hasConflictsOutsidePage=true。
        var page0 = service.search("fengshen", "5.1", "权限撤销 传播时间", null, 1, 0);
        var page1 = service.search("fengshen", "5.1", "权限撤销 传播时间", null, 1, 1);

        assertThat(page0.claims()).hasSize(1);
        assertThat(page1.claims()).hasSize(1);
        assertThat(page0.answerStatus()).isEqualTo(AnswerStatus.CONFLICTED);
        assertThat(page1.answerStatus()).isEqualTo(AnswerStatus.CONFLICTED);
        assertThat(page0.conflicts()).isEmpty();
        assertThat(page0.hasConflictsOutsidePage()).isTrue();
        assertThat(page1.conflicts()).isEmpty();
        assertThat(page1.hasConflictsOutsidePage()).isTrue();

        // 全量单页（limit 20）时冲突详情可见且无页外冲突。
        var full = service.search("fengshen", "5.1", "权限撤销 传播时间", null, 20, 0);
        assertThat(full.conflicts()).isNotEmpty();
        assertThat(full.answerStatus()).isEqualTo(AnswerStatus.CONFLICTED);
        assertThat(full.hasConflictsOutsidePage()).isFalse();
    }

    // ---------------- fixtures ----------------

    private void saveActiveAnnotations(SQLiteRequirementSemanticStore store, String sourceRevision,
                                       List<SemanticAnnotationRecord> records) {
        for (SemanticAnnotationRecord record : records) store.save(record);
        String buildId = SQLiteRequirementSemanticStore.buildId("fengshen", "doc", "5.1", sourceRevision,
                "test-model", "requirement-semantic-v1", "v1");
        store.recordBuildRun(new SemanticBuildRecord(
                buildId,
                "fengshen", "doc", "5.1", sourceRevision, "test-model", "requirement-semantic-v1", "v1",
                SemanticBuildStatus.SUCCESS, records.size(), 0, records.size(), 0, List.of(),
                Instant.now(), Instant.now(), true), records.stream()
                .map(record -> new RequirementSemanticModels.SemanticBuildInput(
                        record.sourceChunkId(), record.windowId(), record.contentHash()))
                .toList());
    }

    private SemanticAnnotationRecord annotation(String annotationId, String sourceRevision,
                                                SemanticAnnotationResult result) {
        return new SemanticAnnotationRecord(annotationId, "fengshen", "doc", "5.1", sourceRevision,
                "file.md|parent-1|0", "parent-1", null, 0, 0, 0, "file.md", 0,
                "hash-" + annotationId, "原始文本", "摘要", "语义文本", result,
                "test-model", "requirement-semantic-v1", "v1",
                ExtractionStatus.SUCCEEDED, ClaimStatus.CANDIDATE, null, 1, 1, 1, 10,
                null, Instant.now(), Instant.now());
    }
}
