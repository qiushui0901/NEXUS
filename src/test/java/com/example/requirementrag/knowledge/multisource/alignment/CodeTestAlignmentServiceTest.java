package com.example.requirementrag.knowledge.multisource.alignment;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestCaseClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestResultClaim;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.AlignmentRelation;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BuildResult;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.CodeSymbolView;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DriftItem;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.LoadedCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeTestAlignmentServiceTest {
    @TempDir Path tempDir;

    @Test
    void buildsVerifiesConfirmsAndTestDrift() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        TestCaseClaim testCase = new TestCaseClaim(
                "tc-1", "immortal", "5.1", "TC-001", "火球冷却测试", "combat",
                "", "", "冷却为 12 秒", "REQ-001", "JUnit", "FireballTest.java",
                "fireballCooldownShouldBe12", "FireballTest.java#tc", KnowledgeStatus.SUPPORTED);
        TestResultClaim passed = new TestResultClaim(
                "tr-1", "immortal", "5.1", "run-1", "TC-001", "PASSED",
                "2026-01-01T00:00:00Z", "staging", "12", "", "run#tr-1", KnowledgeStatus.SUPPORTED);
        TestResultClaim failed = new TestResultClaim(
                "tr-2", "immortal", "5.1", "run-2", "TC-001", "FAILED",
                "2026-01-02T00:00:00Z", "staging", "10", "expected 12", "run#tr-2", KnowledgeStatus.SUPPORTED);

        List<CodeSymbolView> symbols = List.of(
                AlignmentTestSupport.symbol("s-1", "method", "com.game.test.FireballTest",
                        "fireballCooldownShouldBe12", "FireballTest.java", 1, 8, true));
        LoadedCode loaded = AlignmentTestSupport.loadedCode(symbols);
        CodeTestAlignmentService service = new CodeTestAlignmentService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(loaded),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(loaded)));

        AlignmentTestSupport.seed(stores, "immortal", "5.1", List.of(), List.of(),
                List.of(testCase), List.of(passed, failed), List.of());

        BuildResult result = service.build("immortal", "5.1", "staging");

        assertThat(result.relations()).isPositive();
        assertThat(result.drifts()).isPositive();

        List<AlignmentRelation> verifies = service.relations("immortal", "5.1", "staging", "VERIFIES");
        assertThat(verifies).singleElement().satisfies(relation -> {
            assertThat(relation.sourceClaimId()).isEqualTo("tc-1");
            assertThat(relation.targetExternalId()).isEqualTo("s-1");
            assertThat(relation.versionContextId()).isNotBlank();
        });
        List<AlignmentRelation> confirms = service.relations("immortal", "5.1", "staging", "CONFIRMS");
        assertThat(confirms).hasSize(2);
        assertThat(confirms).allSatisfy(relation -> assertThat(relation.relationType()).isEqualTo("CONFIRMS"));

        String contextId = stores.alignment()
                .findVersionContext("immortal", "5.1", "staging").orElseThrow().contextId();
        List<DriftItem> drifts = stores.alignment().findDriftItems("immortal", "5.1", contextId, "TEST_DRIFT");
        assertThat(drifts).isNotEmpty();
    }

    @Test
    void emitsTestDriftEvenWithoutCodeMapping() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        TestCaseClaim testCase = new TestCaseClaim(
                "tc-1", "immortal", "5.1", "TC-001", "火球冷却测试", "combat",
                "", "", "冷却为 12 秒", "REQ-001", "JUnit", "FireballTest.java",
                "fireballCooldownShouldBe12", "FireballTest.java#tc", KnowledgeStatus.SUPPORTED);
        TestResultClaim failed = new TestResultClaim(
                "tr-2", "immortal", "5.1", "run-2", "TC-001", "FAILED",
                "2026-01-02T00:00:00Z", "staging", "10", "expected 12", "run#tr-2", KnowledgeStatus.SUPPORTED);

        LoadedCode loaded = AlignmentTestSupport.loadedCode(List.of());
        CodeTestAlignmentService service = new CodeTestAlignmentService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(loaded),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(loaded)));

        AlignmentTestSupport.seed(stores, "immortal", "5.1", List.of(), List.of(),
                List.of(testCase), List.of(failed), List.of());

        BuildResult result = service.build("immortal", "5.1", "staging");

        assertThat(result.drifts()).isPositive();
        String contextId = stores.alignment()
                .findVersionContext("immortal", "5.1", "staging").orElseThrow().contextId();
        List<DriftItem> drifts = stores.alignment().findDriftItems("immortal", "5.1", contextId, "TEST_DRIFT");
        assertThat(drifts).isNotEmpty();
    }
}
