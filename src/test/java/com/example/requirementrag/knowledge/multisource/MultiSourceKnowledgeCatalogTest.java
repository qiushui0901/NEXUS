package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.CatalogReference;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocument;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocumentVersion;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MultiSourceKnowledgeCatalogTest {
    @TempDir Path tempDir;

    private MultiSourceKnowledgeStore store;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        store = new MultiSourceKnowledgeStore(tempDir.resolve("catalog.db").toString(), objectMapper);
    }

    @Test
    void documentRegistrationIsIdempotent() {
        KnowledgeDocument first = document("doc-1", "fengshen", "combat-requirement");
        KnowledgeDocument duplicate = document("doc-1", "fengshen", "combat-requirement");

        assertThat(store.registerDocument(first)).isEqualTo("doc-1");
        assertThat(store.registerDocument(duplicate)).isEqualTo("doc-1");
    }

    @Test
    void documentVersionIsIdempotentButIsolatedByBusinessVersion() {
        KnowledgeDocumentVersion v51 = version("doc-1", "dv-51", "5.1", "HASH-A");
        KnowledgeDocumentVersion v51Again = version("doc-1", "dv-51b", "5.1", "HASH-A");
        KnowledgeDocumentVersion v52 = version("doc-1", "dv-52", "5.2", "HASH-A");

        assertThat(store.upsertDocumentVersion(v51).documentVersionId()).isEqualTo("dv-51");
        assertThat(store.upsertDocumentVersion(v51Again).documentVersionId()).isEqualTo("dv-51");
        assertThat(store.upsertDocumentVersion(v52).documentVersionId()).isEqualTo("dv-52");

        assertThat(store.findDocumentVersion("doc-1", "5.1", "HASH-A", "v1", "v1")).isPresent();
        assertThat(store.findDocumentVersion("doc-1", "5.2", "HASH-A", "v1", "v1")).isPresent();
    }

    @Test
    void evidenceIdIsStableAndSaveIsIdempotent() {
        KnowledgeDocumentVersion version = version("doc-1", "dv-1", "5.1", "HASH");
        store.upsertDocumentVersion(version);
        String evidenceId = KnowledgeEvidenceIdGenerator.generate(
                "fengshen", "dv-1", "combat.md#3.2/paragraph-4", "excerpt-hash-1");
        KnowledgeEvidence evidence = evidence(evidenceId, "dv-1", "requirements.md#3.2", "excerpt-1", "excerpt-hash-1");

        String saved = store.saveEvidence(evidence);
        String savedAgain = store.saveEvidence(
                evidence(evidenceId, "dv-1", "requirements.md#3.2", "excerpt-1", "excerpt-hash-1"));

        assertThat(saved).isEqualTo(savedAgain).isEqualTo(evidenceId);
        assertThat(KnowledgeEvidenceIdGenerator.generate(
                "fengshen", "dv-1", "combat.md#3.2/paragraph-4", "excerpt-hash-1"))
                .isEqualTo(evidenceId);
        assertThat(store.findEvidenceById(evidenceId)).isPresent();
        assertThat(store.findEvidenceByDocumentVersion("dv-1")).hasSize(1);
    }

    @Test
    void fourBusinessTablesCanBeLinkedBackToCatalog() throws Exception {
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

        store.registerDocument(document("doc-param", "fengshen", "battle-parameter"));
        KnowledgeDocumentVersion version = version("doc-param", "dv-param", "5.1", "param-hash");
        store.upsertDocumentVersion(version);
        KnowledgeEvidence evidence = evidence(
                KnowledgeEvidenceIdGenerator.generate("fengshen", "dv-param", "封神数值.xlsx#技能参数!B12:G12", "param-hash"),
                "dv-param", "封神数值.xlsx#技能参数!B12:G12", "5分钟", "param-hash");
        store.saveEvidence(evidence);

        String paramClaimId = parameters.get(0).claimId();
        store.linkClaimToCatalog("PARAMETER_TABLE", paramClaimId, "dv-param", evidence.evidenceId());
        store.linkClaimToCatalog("DOUBT", doubts.get(0).doubtId(), "dv-param", evidence.evidenceId());
        store.linkClaimToCatalog("TEST_CASE", testCases.get(0).claimId(), "dv-param", evidence.evidenceId());
        store.linkClaimToCatalog("TEST_RESULT", testResults.get(0).claimId(), "dv-param", evidence.evidenceId());

        assertThat(store.findCatalogReference("PARAMETER_TABLE", paramClaimId))
                .contains(new CatalogReference("dv-param", evidence.evidenceId()));
        assertThat(store.findCatalogReference("DOUBT", doubts.get(0).doubtId()))
                .contains(new CatalogReference("dv-param", evidence.evidenceId()));
        assertThat(store.findCatalogReference("TEST_CASE", testCases.get(0).claimId()))
                .contains(new CatalogReference("dv-param", evidence.evidenceId()));
        assertThat(store.findCatalogReference("TEST_RESULT", testResults.get(0).claimId()))
                .contains(new CatalogReference("dv-param", evidence.evidenceId()));
    }

    private KnowledgeDocument document(String id, String projectId, String logicalName) {
        return new KnowledgeDocument(id, projectId, SourceType.REQUIREMENT, logicalName,
                logicalName + ".docx", "file:///data/" + logicalName + ".docx",
                Authority.PRIMARY, "2026-08-23T00:00:00Z");
    }

    private KnowledgeDocumentVersion version(String documentId, String versionId, String businessVersion,
                                             String contentHash) {
        return new KnowledgeDocumentVersion(versionId, documentId, "fengshen", businessVersion,
                contentHash, "v1", "v1", null, "DRAFT", "2026-08-23T00:00:00Z", null);
    }

    private KnowledgeEvidence evidence(String evidenceId, String documentVersionId, String locator,
                                       String excerpt, String excerptHash) {
        return new KnowledgeEvidence(evidenceId, documentVersionId, "fengshen",
                SourceType.REQUIREMENT, locator, excerpt, excerptHash,
                null, null, null, null, null, null, null, null, "2026-08-23T00:00:00Z");
    }
}