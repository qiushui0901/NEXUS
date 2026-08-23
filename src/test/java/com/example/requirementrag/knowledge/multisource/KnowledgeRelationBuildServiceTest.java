package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.ExtractionRun;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocument;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocumentVersion;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeEvidence;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeRelation;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRelationBuildServiceTest {
    @TempDir Path tempDir;

    private MultiSourceKnowledgeStore store;
    private String evidenceId;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        store = new MultiSourceKnowledgeStore(tempDir.resolve("relation.db").toString(), objectMapper);
        store.registerDocument(new KnowledgeDocument("doc-1", "fengshen", SourceType.REQUIREMENT,
                "combat-requirement", "combat.docx", "file:///data/combat.docx", Authority.PRIMARY, null));
        store.upsertDocumentVersion(new KnowledgeDocumentVersion("dv-1", "doc-1", "fengshen", "5.1",
                "hash-1", "v1", "v1", null, "DRAFT", null, null));
        evidenceId = KnowledgeEvidenceIdGenerator.generate("fengshen", "dv-1", "combat.md#3.2", "excerpt-1");
        store.saveEvidence(new KnowledgeEvidence(evidenceId, "dv-1", "fengshen", SourceType.REQUIREMENT,
                "combat.md#3.2", "excerpt", "excerpt-1", null, null, null, null, null, null, null, null, null));
        // 需求与测试用例 Claim 先落主库，满足关系外键
        store.saveClaim(requirementClaim());
        store.saveClaim(testCaseClaim());
    }

    @Test
    void offlineBuildProducesRuleProposedRelationsAndRecordsExtractionRun() {
        KnowledgeRelationBuildService service = new KnowledgeRelationBuildService(
                store, new CrossSourceRelationExtractor(), null,
                new MultiSourceKnowledgeProperties(true, false, null, Map.of(), false));

        KnowledgeRelationBuildService.BuildResult result = service.buildRelations(
                "fengshen", "5.1", "dv-1", List.of(requirement(), testCase()), List.of(),
                Map.of("tc:1", evidenceId));

        assertThat(result.produced()).isEqualTo(1);
        assertThat(result.rejected()).isZero();

        List<KnowledgeRelation> relations = store.findRelationsForClaims("fengshen", "5.1", Set.of("tc:1", "req:1"));
        assertThat(relations).hasSize(1);
        assertThat(relations.get(0).status()).isEqualTo("RULE_PROPOSED");
        assertThat(relations.get(0).relationType()).isEqualTo("VERIFIES");
        assertThat(relations.get(0).evidenceId()).isEqualTo(evidenceId);

        // 抽取运行审计成功且可查询
        assertThat(store.findExtractionRun(result.extractionRunId())).isPresent();
        assertThat(store.findExtractionRun(result.extractionRunId()).orElseThrow().status()).isEqualTo("SUCCESS");
    }

    @Test
    void llmRejectionIsPersistedForAudit() {
        KnowledgeRelationBuildService service = new KnowledgeRelationBuildService(
                store, new CrossSourceRelationExtractor(),
                (source, relationType, target, evidence) ->
                        new CrossSourceRelationConfirmer.Confirmation(false, "不相关"),
                new MultiSourceKnowledgeProperties(true, false, null, Map.of(), true));

        KnowledgeRelationBuildService.BuildResult result = service.buildRelations(
                "fengshen", "5.1", "dv-1", List.of(requirement(), testCase()), List.of(),
                Map.of("tc:1", evidenceId));

        assertThat(result.produced()).isZero();
        assertThat(result.rejected()).isEqualTo(1);

        List<KnowledgeRelation> relations = store.findRelationsForClaims("fengshen", "5.1", Set.of("tc:1", "req:1"));
        assertThat(relations).hasSize(1);
        assertThat(relations.get(0).status()).isEqualTo("LLM_REJECTED");
        assertThat(relations.get(0).confirmationMethod()).isEqualTo("LLM");
    }

    @Test
    void llmConfirmationUpgradesRelationStatus() {
        KnowledgeRelationBuildService service = new KnowledgeRelationBuildService(
                store, new CrossSourceRelationExtractor(),
                (source, relationType, target, evidence) ->
                        new CrossSourceRelationConfirmer.Confirmation(true, "匹配"),
                new MultiSourceKnowledgeProperties(true, false, null, Map.of(), true));

        KnowledgeRelationBuildService.BuildResult result = service.buildRelations(
                "fengshen", "5.1", "dv-1", List.of(requirement(), testCase()), List.of(),
                Map.of("tc:1", evidenceId));

        assertThat(result.produced()).isEqualTo(1);
        List<KnowledgeRelation> relations = store.findRelationsForClaims("fengshen", "5.1", Set.of("tc:1", "req:1"));
        assertThat(relations).hasSize(1);
        assertThat(relations.get(0).status()).isEqualTo("LLM_CONFIRMED");
        assertThat(relations.get(0).confirmationReason()).isEqualTo("匹配");
    }

    private KnowledgeClaimRecord requirementClaim() {
        return new KnowledgeClaimRecord("req:1", "fengshen", "dv-1", SourceType.REQUIREMENT,
                Authority.PRIMARY, "fengshen|5.1|订单|订单-001", "订单-001", "允许取消", "允许",
                "TEXT", null, "VERIFIED", null, null, null, "RULE", null, null, null);
    }

    private KnowledgeClaimRecord testCaseClaim() {
        return new KnowledgeClaimRecord("tc:1", "fengshen", "dv-1", SourceType.TEST_CASE,
                Authority.SECONDARY, "fengshen|5.1|订单|订单-001", "取消订单", "expectedResult", "可取消",
                "TEXT", null, "SUPPORTED", null, null, null, "RULE", null, null, null);
    }

    private UnifiedKnowledgeClaim requirement() {
        return new UnifiedKnowledgeClaim("req:1", "fengshen", "5.1", "fengshen|5.1|订单|订单-001",
                "订单-001", "允许取消", "允许", "TEXT", null, SourceType.REQUIREMENT,
                Authority.PRIMARY, MultiSourceKnowledgeModels.KnowledgeStatus.VERIFIED,
                "5.1", null, "combat.md#3.2", "订单");
    }

    private UnifiedKnowledgeClaim testCase() {
        return new UnifiedKnowledgeClaim("tc:1", "fengshen", "5.1", "fengshen|5.1|订单|订单-001",
                "取消订单", "expectedResult", "可取消", "TEXT", null, SourceType.TEST_CASE,
                Authority.SECONDARY, MultiSourceKnowledgeModels.KnowledgeStatus.SUPPORTED,
                "5.1", null, "OrderTest.java#tc-1", "订单");
    }
}