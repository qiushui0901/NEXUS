package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** REQUIREMENT_SEMANTIC 候选适配器：投影、门禁（意图 / INFERRED）、active revision 隔离与降级。 */
class RequirementSemanticCandidateAdapterTest {
    @TempDir Path tempDir;

    private SQLiteRequirementSemanticStore store;
    private RequirementSemanticProperties properties;
    private RequirementSemanticCandidateAdapter adapter;

    @BeforeEach
    void setUp() {
        // 默认：候选召回开启，规范意图门禁关闭（生产默认形态）。
        properties = properties(true, false, false);
        store = new SQLiteRequirementSemanticStore(new ObjectMapper(), properties);
        adapter = new RequirementSemanticCandidateAdapter(store, properties);
    }

    private RequirementSemanticProperties properties(boolean candidateRetrieval, boolean normativeRetrieval,
                                                     boolean allowInferred) {
        return new RequirementSemanticProperties(true, candidateRetrieval, normativeRetrieval, false,
                tempDir.resolve("semantic.db").toString(), "test-model",
                "requirement-semantic-v1", "v1", 12_000, 30, 30, 30, 30, 20, 30, 0,
                1_000, 1_800, 1_000_000, 400, allowInferred);
    }

    @Test
    void projectsActiveAnnotationsIntoUnifiedCandidateClaims() {
        saveActiveAnnotation("rev-1", annotation("a-1", "rev-1", result(
                new RequirementSemanticModels.SemanticEntity("成长基金", "CURRENCY",
                        List.of(), "EXPLICIT", "玩家达到30级后开放成长基金"),
                new RequirementSemanticModels.SemanticCondition("成长基金", "reward_currency",
                        "EQ", "灵玉", "", "STRING", "", "EXPLICIT", "奖励为灵玉"),
                new RequirementSemanticModels.SemanticNumericFact("成长基金", "unlock_level",
                        "30级", 30.0, "级", "级", "GTE", "EXPLICIT", "达到30级"),
                new RequirementSemanticModels.SemanticClaimCandidate("growth_fund.currency",
                        "成长基金", "currency", "灵玉", "", "EXPLICIT", "奖励为灵玉"))));

        List<UnifiedKnowledgeClaim> claims = adapter.load("p1", "5.1", "成长基金 奖励", KnowledgeQueryIntent.GENERAL);

        assertThat(claims).hasSize(4);
        assertThat(claims).allSatisfy(claim -> {
            assertThat(claim.sourceType()).isEqualTo(SourceType.REQUIREMENT_SEMANTIC);
            assertThat(claim.status()).isEqualTo(KnowledgeStatus.EXTRACTED);
            assertThat(claim.authority()).isEqualTo(Authority.SECONDARY);
            assertThat(claim.projectId()).isEqualTo("p1");
            assertThat(claim.version()).isEqualTo("5.1");
            assertThat(claim.evidenceLocation()).startsWith("requirement-semantic:a-1#");
        });
        UnifiedKnowledgeClaim condition = claims.stream()
                .filter(claim -> claim.claimId().endsWith("#condition-0")).findFirst().orElseThrow();
        assertThat(condition.subject()).isEqualTo("成长基金");
        assertThat(condition.predicate()).isEqualTo("reward_currency");
        assertThat(condition.value()).isEqualTo("灵玉");
        assertThat(condition.valueType()).isEqualTo("STRING");
        assertThat(condition.factKey()).isEqualTo("p1|5.1|成长基金|reward_currency");
        UnifiedKnowledgeClaim numeric = claims.stream()
                .filter(claim -> claim.claimId().endsWith("#numeric-0")).findFirst().orElseThrow();
        assertThat(numeric.value()).isEqualTo("30");
        assertThat(numeric.valueType()).isEqualTo("NUMBER");
    }

    @Test
    void returnsEmptyWhenCandidateRetrievalDisabled() {
        adapter = new RequirementSemanticCandidateAdapter(store,
                properties(false, false, false));

        assertThat(adapter.load("p1", "5.1", "成长基金", KnowledgeQueryIntent.GENERAL)).isEmpty();
    }

    @Test
    void normativeIntentRequiresExplicitNormativeRetrievalFlag() {
        saveActiveAnnotation("rev-1", annotation("a-1", "rev-1", result(
                new RequirementSemanticModels.SemanticEntity("成长基金", "CURRENCY",
                        List.of(), "EXPLICIT", "玩家达到30级后开放成长基金"),
                null, null, null)));

        // normative-retrieval-enabled=false：规范事实意图下语义候选不可见。
        assertThat(adapter.load("p1", "5.1", "成长基金", KnowledgeQueryIntent.NORMATIVE)).isEmpty();
        assertThat(adapter.load("p1", "5.1", "成长基金", KnowledgeQueryIntent.GENERAL)).isNotEmpty();

        // 显式开启后语义候选才进入规范意图。
        RequirementSemanticProperties enabled = properties(true, true, false);
        RequirementSemanticCandidateAdapter normativeAdapter =
                new RequirementSemanticCandidateAdapter(store, enabled);
        assertThat(normativeAdapter.load("p1", "5.1", "成长基金", KnowledgeQueryIntent.NORMATIVE)).isNotEmpty();
    }

    @Test
    void inferredCandidatesExcludedUnlessExplicitlyAllowed() {
        saveActiveAnnotation("rev-1", annotation("a-1", "rev-1", result(
                new RequirementSemanticModels.SemanticEntity("成长基金", "CURRENCY",
                        List.of(), "EXPLICIT", "玩家达到30级后开放成长基金"),
                new RequirementSemanticModels.SemanticCondition("转盘", "hidden_rule",
                        "EQ", "神秘规则", "", "STRING", "", "INFERRED", "模型推断的隐藏规则"),
                null,
                new RequirementSemanticModels.SemanticClaimCandidate("wheel.hidden", "转盘",
                        "hidden_rule", "神秘规则", "", "UNKNOWN", "模型推断的隐藏规则"))));

        List<UnifiedKnowledgeClaim> claims = adapter.load("p1", "5.1", "转盘", KnowledgeQueryIntent.GENERAL);

        // INFERRED / UNKNOWN 默认不进入候选，只有 EXPLICIT 实体保留。
        assertThat(claims).hasSize(1);
        assertThat(claims.get(0).subject()).isEqualTo("成长基金");

        RequirementSemanticProperties allowInferred = properties(true, false, true);
        RequirementSemanticCandidateAdapter inferredAdapter =
                new RequirementSemanticCandidateAdapter(store, allowInferred);
        assertThat(inferredAdapter.load("p1", "5.1", "转盘", KnowledgeQueryIntent.GENERAL)).hasSize(3);
    }

    @Test
    void onlyActiveBuildAnnotationsAreVisible() {
        // FAILED 构建不激活：其 revision 下的标注不可见。
        saveAnnotation(annotation("a-failed", "rev-failed", result(
                new RequirementSemanticModels.SemanticEntity("失败实体", "SYSTEM",
                        List.of(), "EXPLICIT", "失败构建的实体"),
                null, null, null)));
        store.saveBuild(build("rev-failed", SemanticBuildStatus.FAILED, false));
        saveActiveAnnotation("rev-ok", annotation("a-ok", "rev-ok", result(
                new RequirementSemanticModels.SemanticEntity("成长基金", "CURRENCY",
                        List.of(), "EXPLICIT", "玩家达到30级后开放成长基金"),
                null, null, null)));

        List<UnifiedKnowledgeClaim> claims = adapter.load("p1", "5.1", "成长基金", KnowledgeQueryIntent.GENERAL);

        assertThat(claims).hasSize(1);
        assertThat(claims.get(0).subject()).isEqualTo("成长基金");
        // 非 active revision 的旧构建标注（即使 extraction_status=SUCCEEDED）不可见。
        assertThat(store.listActiveByProjectVersion("p1", "5.1", 100)).hasSize(1);
    }

    @Test
    void overlappingWindowClaimsCollapseIntoSingleCandidate() {
        // 两个窗口对同一事实重复标注（重叠窗口场景）：按（主体|谓词|值|单位|值类型）折叠。
        saveActiveAnnotation("rev-1", annotation("a-1", "rev-1", result(
                null,
                new RequirementSemanticModels.SemanticCondition("成长基金", "reward_currency",
                        "EQ", "灵玉", "", "STRING", "", "EXPLICIT", "奖励为灵玉"),
                null, null)));
        saveActiveAnnotation("rev-1", annotation("a-2", "rev-1", result(
                null,
                new RequirementSemanticModels.SemanticCondition("成长基金", "reward_currency",
                        "EQ", "灵玉", "", "STRING", "", "EXPLICIT", "奖励为灵玉（重叠窗口重复）"),
                null, null)));

        List<UnifiedKnowledgeClaim> claims = adapter.load("p1", "5.1", "成长基金", KnowledgeQueryIntent.GENERAL);

        assertThat(claims).hasSize(1);
    }

    @Test
    void storeFailureDegradesToEmptyCandidates() {
        SQLiteRequirementSemanticStore failing = mock(SQLiteRequirementSemanticStore.class);
        when(failing.listActiveByProjectVersion(anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("database is locked"));
        RequirementSemanticCandidateAdapter failingAdapter =
                new RequirementSemanticCandidateAdapter(failing, properties);

        assertThat(failingAdapter.load("p1", "5.1", "成长基金", KnowledgeQueryIntent.GENERAL)).isEmpty();
    }

    // ---------------- fixtures ----------------

    private SemanticAnnotationResult result(RequirementSemanticModels.SemanticEntity entity,
                                            RequirementSemanticModels.SemanticCondition condition,
                                            RequirementSemanticModels.SemanticNumericFact numericFact,
                                            RequirementSemanticModels.SemanticClaimCandidate claim) {
        return new SemanticAnnotationResult(
                entity == null ? List.of() : List.of(entity),
                condition == null ? List.of() : List.of(condition),
                List.of(),
                numericFact == null ? List.of() : List.of(numericFact),
                claim == null ? List.of() : List.of(claim),
                List.of(), List.of(), List.of(), true);
    }

    private SemanticAnnotationRecord annotation(String annotationId, String sourceRevision,
                                                SemanticAnnotationResult result) {
        return new SemanticAnnotationRecord(annotationId, "p1", "doc", "5.1", sourceRevision,
                "file.md|parent-1|0", "parent-1", null, 0, 0, 0, "file.md", 0,
                "hash-" + annotationId, "原始文本", "摘要", "语义文本", result,
                "test-model", "requirement-semantic-v1", "v1",
                ExtractionStatus.SUCCEEDED, ClaimStatus.CANDIDATE, null, 1, 1, 1, 10,
                null, Instant.now(), Instant.now());
    }

    private SemanticBuildRecord build(String sourceRevision, SemanticBuildStatus status, boolean active) {
        return new SemanticBuildRecord(
                SQLiteRequirementSemanticStore.buildId("p1", "doc", "5.1", sourceRevision,
                        "test-model", "requirement-semantic-v1", "v1"),
                "p1", "doc", "5.1", sourceRevision, "test-model", "requirement-semantic-v1", "v1",
                status, 1, 0, 1, 0, List.of(), Instant.now(), Instant.now(), active);
    }

    private void saveActiveAnnotation(String sourceRevision, SemanticAnnotationRecord record) {
        saveAnnotation(record);
        SemanticBuildRecord build = build(sourceRevision, SemanticBuildStatus.SUCCESS, true);
        store.saveBuild(build);
        store.saveBuildInputs(build.buildId(), List.of(new RequirementSemanticModels.SemanticBuildInput(
                record.sourceChunkId(), record.windowId(), record.contentHash())));
    }

    private void saveAnnotation(SemanticAnnotationRecord record) {
        store.save(record);
    }
}
