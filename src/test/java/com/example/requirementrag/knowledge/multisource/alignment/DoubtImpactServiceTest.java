package com.example.requirementrag.knowledge.multisource.alignment;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterValueType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestCaseClaim;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.CodeSymbolView;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DoubtImpact;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DoubtImpactBuildResult;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.LoadedCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DoubtImpactServiceTest {
    @TempDir Path tempDir;

    @Test
    void buildsImpactForOpenDoubtAndResolvesWithEvidence() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);

        ParameterClaim param = new ParameterClaim(
                "p-1", "immortal", "5.1", "skills.xlsx", "技能参数", 1, "A1", "combat",
                "Fireball_CD", "12", "12", "秒", null, null, 0, true,
                ParameterValueType.INTEGER, "immortal|5.1|combat|fireball_cd|value",
                "skills.xlsx#A1", KnowledgeStatus.SUPPORTED);
        TestCaseClaim testCase = new TestCaseClaim(
                "tc-1", "immortal", "5.1", "TC-001", "火球冷却测试", "combat",
                "", "", "", "REQ-001", "JUnit", "FireballTest.java",
                "fireballCooldownShouldBe12", "FireballTest.java#tc", KnowledgeStatus.SUPPORTED);
        DoubtClaim doubt = new DoubtClaim(
                "d-1", "immortal", "5.1", "combat", "火球冷却是否 12 秒？", null,
                "QA存疑", 1, DoubtStatus.OPEN, "tester", "P1", "2026-12-31", List.of(), "qa.xlsx#A1");

        AlignmentTestSupport.seed(stores, "immortal", "5.1", List.of(param), List.of(doubt),
                List.of(testCase), List.of(), List.of());

        List<CodeSymbolView> symbols = List.of(
                AlignmentTestSupport.symbol("s-1", "method", "com.game.skill.resolveFireballCd",
                        "Fireball_CD", "FireballSkill.java", 1, 5, false));
        LoadedCode loaded = AlignmentTestSupport.loadedCode(symbols);
        VersionContextService versionContextService =
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(loaded));
        BusinessConceptService conceptService = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(loaded),
                versionContextService);
        DoubtImpactService service = new DoubtImpactService(
                stores.multiSource(), stores.alignment(), conceptService, versionContextService);

        DoubtImpactBuildResult result = service.build("immortal", "5.1", "staging");

        assertThat(result.totalImpacts()).isPositive();
        assertThat(result.affectedDoubts()).isEqualTo(1);
        String contextId = stores.alignment()
                .findVersionContext("immortal", "5.1", "staging").orElseThrow().contextId();
        List<DoubtImpact> impacts = service.impacts("immortal", "5.1", "staging", "OPEN");
        assertThat(impacts).extracting(DoubtImpact::targetType)
                .contains("CODE", "PARAMETER_TABLE", "TEST_CASE");
        assertThat(impacts).allSatisfy(item -> assertThat(item.status()).isEqualTo("OPEN"));

        List<DoubtImpact> resolved = service.resolve("immortal", "5.1", "staging",
                "d-1", "已确认 12 秒", "ev-99");
        assertThat(resolved).isNotEmpty();
        assertThat(resolved).allSatisfy(item -> {
            assertThat(item.status()).isEqualTo("RESOLVED");
            assertThat(item.resolutionConclusion()).isEqualTo("已确认 12 秒");
            assertThat(item.resolutionEvidenceId()).isEqualTo("ev-99");
        });
        assertThat(stores.alignment().findDoubtImpacts("immortal", "5.1", contextId, "OPEN")).isEmpty();
    }
}
