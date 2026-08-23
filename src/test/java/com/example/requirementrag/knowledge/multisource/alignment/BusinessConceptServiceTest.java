package com.example.requirementrag.knowledge.multisource.alignment;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterValueType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestCaseClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestResultClaim;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BuildResult;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BusinessConcept;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.CodeSymbolView;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.ConceptMember;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.ConceptAlias;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessConceptServiceTest {
    @TempDir Path tempDir;

    @Test
    void buildsConceptsAndAttachesCodeSymbols() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        ParameterClaim param = new ParameterClaim(
                "p-1", "immortal", "5.1", "skills.xlsx", "技能参数", 12, "B12", "combat",
                "Fireball_CD", "12", "12", "秒", null, null, 0, true,
                ParameterValueType.INTEGER, "immortal|5.1|combat|fireball_cd|value",
                "skills.xlsx#技能参数!B12", KnowledgeStatus.SUPPORTED);
        TestCaseClaim testCase = new TestCaseClaim(
                "tc-1", "immortal", "5.1", "TC-001", "火球冷却测试", "combat",
                "", "", "", "REQ-001", "JUnit", "FireballTest.java", "fireballCooldownShouldBe12",
                "FireballTest.java#fireball", KnowledgeStatus.SUPPORTED);
        TestResultClaim testResult = new TestResultClaim(
                "tr-1", "immortal", "5.1", "run-1", "TC-001", "PASSED",
                "2026-01-01T00:00:00Z", "staging", "12", "", "FireballTest.java#result", KnowledgeStatus.SUPPORTED);
        DoubtClaim doubt = new DoubtClaim(
                "d-1", "immortal", "5.1", "combat", "火球冷却是否 12 秒？", null,
                "QA存疑", 1, com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtStatus.OPEN,
                "tester", "P1", null, List.of(), "qa.xlsx#A1");

        List<CodeSymbolView> symbols = List.of(
                AlignmentTestSupport.symbol("s-1", "method", "com.game.skill.resolveFireballCd",
                        "Fireball_CD", "FireballSkill.java", 10, 30, false));

        AlignmentTestSupport.seed(stores, "immortal", "5.1", List.of(param), List.of(doubt),
                List.of(testCase), List.of(testResult), List.of());

        BusinessConceptService service = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(
                        AlignmentTestSupport.loadedCode(symbols)));

        BuildResult result = service.build("immortal", "5.1");

        assertThat(result.concepts()).isPositive();
        assertThat(result.aliases()).isPositive();
        assertThat(result.members()).isPositive();

        BusinessConcept concept = stores.alignment()
                .findConceptByKey("immortal", "param:combat.fireballcd")
                .orElseThrow();
        assertThat(concept.displayName()).isEqualTo("Fireball_CD");
        assertThat(stores.alignment().findAliases("immortal", concept.conceptId()))
                .extracting(ConceptAlias::alias).contains("Fireball_CD");
        assertThat(stores.alignment().findMembers("immortal", concept.conceptId()))
                .extracting(ConceptMember::sourceType)
                .contains("PARAMETER_TABLE", "CODE");
    }

    @Test
    void buildIsIdempotent() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        ParameterClaim param = new ParameterClaim(
                "p-1", "immortal", "5.1", "skills.xlsx", "技能参数", 1, "A1", "combat",
                "Cooldown", "5", "5", "秒", null, null, 0, true,
                ParameterValueType.INTEGER, "immortal|5.1|combat|cooldown|value",
                "skills.xlsx#A1", KnowledgeStatus.SUPPORTED);
        AlignmentTestSupport.seed(stores, "immortal", "5.1", List.of(param), List.of(),
                List.of(), List.of(), List.of());

        BusinessConceptService service = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(
                        AlignmentTestSupport.loadedCode(List.of())));

        BuildResult first = service.build("immortal", "5.1");
        BuildResult second = service.build("immortal", "5.1");

        assertThat(second.concepts()).isEqualTo(first.concepts());
        assertThat(stores.alignment().findConcepts("immortal")).hasSize(first.concepts());
    }
}