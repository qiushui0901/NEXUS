package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocument;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocumentVersion;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MultiSourceKnowledgeClaimTest {
    @TempDir Path tempDir;

    private MultiSourceKnowledgeStore store;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        store = new MultiSourceKnowledgeStore(tempDir.resolve("claim.db").toString(), objectMapper);
    }

    @Test
    void saveClaimIsIdempotentAndUpdatesTimestamp() {
        KnowledgeClaimRecord first = claim("c-1", "5.1", "fact-1", "subject-1", "v1", "t1");
        KnowledgeClaimRecord second = claim("c-1", "5.1", "fact-1", "subject-1", "v1", "t2");

        store.saveClaim(first);
        store.saveClaim(second);

        assertThat(store.findClaimById("c-1")).isPresent();
        assertThat(store.findClaimById("c-1").orElseThrow().updatedAt()).isEqualTo("t2");
    }

    @Test
    void sameFactKeyDifferentObjectValueCoexist() {
        KnowledgeClaimRecord one = claim("c-1", "5.1", "fengshen|5.1|combat|火球术|冷却时间", "火球术", "10", "t1");
        KnowledgeClaimRecord two = claim("c-2", "5.1", "fengshen|5.1|combat|火球术|冷却时间", "火球术", "12", "t1");

        store.saveClaim(one);
        store.saveClaim(two);

        List<KnowledgeClaimRecord> hits = store.findClaimsByFactKey(
                "fengshen", "dv-1", "fengshen|5.1|combat|火球术|冷却时间");
        assertThat(hits).hasSize(2);
    }

    @Test
    void factKeyGeneratorIsDeterministicAndNormalized() {
        String a = KnowledgeFactKeyGenerator.generate("FengShen", "5.1", " Combat ", " 火球术 ", " 冷却时间 ");
        String b = KnowledgeFactKeyGenerator.generate("fengshen", "5.1", "combat", "火球术", "冷却时间");
        assertThat(a).isEqualTo(b).isEqualTo("fengshen|5.1|combat|火球术|冷却时间");
    }

    @Test
    void syncSnapshotClaimsPersistsUnifiedClaimsAndEvidenceLinks() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ParameterTableLoader loader = new ParameterTableLoader();
        var layout = loader.parseHeaders(List.of("模块", "参数", "值", "单位", "版本"));
        List<MultiSourceKnowledgeModels.ParameterClaim> parameters = loader.parse(layout,
                List.of(Map.of("0", "权限撤销", "1", "传播时间", "2", "5分钟", "3", "分钟", "4", "5.1")),
                "fengshen", "5.1", "参数表.xlsx", "5.1参数");
        DoubtClaimParser doubtParser = new DoubtClaimParser();
        List<MultiSourceKnowledgeModels.DoubtClaim> doubts = List.of(doubtParser.parse(
                Map.of("问题", "权限撤销未确认", "状态", "OPEN"), "fengshen", "5.1", "5.1存疑", 1));
        TestKnowledgeLoaders testLoaders = new TestKnowledgeLoaders(objectMapper);
        List<MultiSourceKnowledgeModels.TestCaseClaim> testCases = List.of(testLoaders.parseTestCase(
                "{\"testCaseId\":\"tc-1\",\"title\":\"取消订单\",\"expectedResult\":\"可取消\","
                        + "\"module\":\"订单\",\"coveredRequirementId\":\"订单-001\",\"framework\":\"JUnit\"}",
                "fengshen", "5.1", "OrderTest.java"));
        List<MultiSourceKnowledgeModels.TestResultClaim> testResults = List.of(testLoaders.parseTestResult(
                "{\"testCaseId\":\"tc-1\",\"testRunId\":\"run-1\",\"executionStatus\":\"PASSED\",\"environment\":\"ci\"}",
                "fengshen", "5.1"));
        store.replaceSnapshot("fengshen", "5.1", parameters, doubts, testCases, testResults);

        // 建立 catalog 版本与一条 Evidence
        store.registerDocument(new KnowledgeDocument("doc-1", "fengshen", SourceType.REQUIREMENT,
                "combat-requirement", "combat.docx", "file:///data/combat.docx", Authority.PRIMARY, null));
        store.upsertDocumentVersion(new KnowledgeDocumentVersion("dv-1", "doc-1", "fengshen", "5.1",
                "hash-1", "v1", "v1", null, "DRAFT", null, null));
        String evidenceId = KnowledgeEvidenceIdGenerator.generate("fengshen", "dv-1", "combat.md#3.2", "excerpt-1");
        store.saveEvidence(new KnowledgeEvidence(evidenceId, "dv-1", "fengshen", SourceType.REQUIREMENT,
                "combat.md#3.2", "excerpt", "excerpt-1", null, null, null, null, null, null, null, null, null));

        // 每个业务 claim 都指向同一 evidence
        Map<String, String> evidenceMap = new LinkedHashMap<>();
        evidenceMap.put(parameters.get(0).claimId(), evidenceId);
        evidenceMap.put(doubts.get(0).doubtId(), evidenceId);
        evidenceMap.put(testCases.get(0).claimId(), evidenceId);
        evidenceMap.put(testResults.get(0).claimId(), evidenceId);
        store.syncSnapshotClaims("fengshen", "5.1", "dv-1", evidenceMap);

        // 四类 claim 均可回查主库
        assertThat(store.findClaimById(parameters.get(0).claimId()))
                .isPresent()
                .get()
                .satisfies(claim -> {
                    assertThat(claim.documentVersionId()).isEqualTo("dv-1");
                    assertThat(claim.sourceType()).isEqualTo(SourceType.PARAMETER_TABLE);
                    assertThat(claim.status()).isEqualTo("SUPPORTED");
                });
        assertThat(store.findClaimById(doubts.get(0).doubtId())).isPresent();
        assertThat(store.findClaimById(testCases.get(0).claimId())).isPresent();
        assertThat(store.findClaimById(testResults.get(0).claimId())).isPresent();

        // Evidence 关联
        assertThat(store.findEvidenceIdsByClaimId(parameters.get(0).claimId())).containsExactly(evidenceId);
        assertThat(store.findEvidenceIdsByClaimId(testCases.get(0).claimId())).containsExactly(evidenceId);

        // 业务表可回查 catalog 关联
        assertThat(store.findCatalogReference("PARAMETER_TABLE", parameters.get(0).claimId()))
                .hasValueSatisfying(ref -> {
                    assertThat(ref.documentVersionId()).isEqualTo("dv-1");
                    assertThat(ref.evidenceId()).isEqualTo(evidenceId);
                });

        // 同一 fact_key 多来源并存
        List<KnowledgeClaimRecord> byFact = store.findClaimsByFactKey("fengshen", "dv-1",
                KnowledgeFactKeyGenerator.generate("fengshen", "5.1", "订单", "取消订单", "expectedResult"));
        assertThat(byFact).isNotEmpty();
    }

    private KnowledgeClaimRecord claim(String id, String businessVersion, String factKey,
                                       String subject, String value, String updatedAt) {
        return new KnowledgeClaimRecord(id, "fengshen", "dv-1", SourceType.PARAMETER_TABLE,
                Authority.PRIMARY, factKey, subject, "value", value, "TEXT", "分钟",
                "SUPPORTED", null, null, null, "RULE", null, "2026-08-23T00:00:00Z", updatedAt);
    }
}