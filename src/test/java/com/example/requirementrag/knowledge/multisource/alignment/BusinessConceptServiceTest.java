package com.example.requirementrag.knowledge.multisource.alignment;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocumentVersion;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
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
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.LoadedCode;
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

        LoadedCode loaded = AlignmentTestSupport.loadedCode(symbols);
        AlignmentTestSupport.seed(stores, "immortal", "5.1", List.of(param), List.of(doubt),
                List.of(testCase), List.of(testResult), List.of());

        BusinessConceptService service = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(loaded),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(loaded)));

        BuildResult result = service.build("immortal", "5.1");

        assertThat(result.concepts()).isPositive();
        assertThat(result.aliases()).isPositive();
        assertThat(result.members()).isPositive();

        BusinessConcept concept = stores.alignment()
                .findConceptByKey("immortal", "combat.fireballcd")
                .orElseThrow();
        assertThat(concept.displayName()).isEqualTo("Fireball_CD");
        assertThat(stores.alignment().findAliases("immortal", concept.conceptId()))
                .extracting(ConceptAlias::alias).contains("Fireball_CD");
        assertThat(stores.alignment().findMembers("immortal", concept.conceptId(), "5.1"))
                .extracting(ConceptMember::sourceType)
                .contains("PARAMETER_TABLE", "CODE");
    }

    @Test
    void buildIsIdempotentAndReplacesMembersForBusinessVersion() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        ParameterClaim param = new ParameterClaim(
                "p-1", "immortal", "5.1", "skills.xlsx", "技能参数", 1, "A1", "combat",
                "Cooldown", "5", "5", "秒", null, null, 0, true,
                ParameterValueType.INTEGER, "immortal|5.1|combat|cooldown|value",
                "skills.xlsx#A1", KnowledgeStatus.SUPPORTED);
        AlignmentTestSupport.seed(stores, "immortal", "5.1", List.of(param), List.of(),
                List.of(), List.of(), List.of());
        LoadedCode firstCode = AlignmentTestSupport.loadedCode(List.of(
                AlignmentTestSupport.symbol("s-old", "method", "com.game.old.OldMethod",
                        "Cooldown", "OldMethod.java", 1, 5, false)));
        LoadedCode secondCode = AlignmentTestSupport.loadedCode(List.of(
                AlignmentTestSupport.symbol("s-new", "method", "com.game.new.NewMethod",
                        "Cooldown", "NewMethod.java", 1, 5, false)));

        BusinessConceptService service = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(firstCode),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(firstCode)));
        service.build("immortal", "5.1");

        // 旧代码仓库只有 s-old；重建时换成 s-new 后，旧 CODE member 必须被清除
        BusinessConceptService newService = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(secondCode),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(secondCode)));
        newService.build("immortal", "5.1");

        BusinessConcept concept = stores.alignment()
                .findConceptByKey("immortal", "combat.cooldown")
                .orElseThrow();
        List<ConceptMember> members = stores.alignment().findMembers("immortal", concept.conceptId(), "5.1");
        assertThat(members).extracting(ConceptMember::externalId).contains("s-new").doesNotContain("s-old");
    }

    @Test
    void buildProjectExcludesDraftVersions() {
        // 未来版本 6.0 处于 DRAFT：不得进入实体层（防 DRAFT 泄漏到公开读取与当前事实）
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        AlignmentTestSupport.seedParameter(stores, "5.1", "LevelCap", "100", "combat");
        String draftDvId = "dv-immortal-6.0";
        stores.multiSource().upsertDocumentVersion(new KnowledgeDocumentVersion(
                draftDvId, "doc-immortal", "immortal", "6.0", "hash-6.0",
                "v1", "v1", null, "DRAFT", null, null));
        stores.multiSource().saveClaim(new KnowledgeClaimRecord(
                "p-6.0", "immortal", draftDvId, SourceType.PARAMETER_TABLE, Authority.PRIMARY,
                "immortal|6.0|combat|levelcap|value", "LevelCap", "value", "999", "INTEGER", "级",
                "SUPPORTED", null, null, null, "RULE", null, null, null));

        BusinessConceptService service = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty()),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty())));
        service.buildProject("immortal");

        assertThat(stores.multiSource().findPublishedBusinessVersions("immortal"))
                .containsExactly("5.1");
        BusinessConcept concept = stores.alignment()
                .findConceptByKey("immortal", "combat.levelcap")
                .orElseThrow();
        assertThat(stores.alignment().findMembers("immortal", concept.conceptId(), null))
                .extracting(ConceptMember::businessVersion)
                .containsExactly("5.1")
                .doesNotContain("6.0");
    }

    @Test
    void versionLevelBuildDoesNotAttachCurrentCodeToHistoricalVersion() {
        // Fix 2：版本级构建只在构建最新已发布版本时挂当前代码，历史版本不得保存当前 commit
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        AlignmentTestSupport.seedParameter(stores, "5.0", "LevelCap", "100", "combat");
        AlignmentTestSupport.seedParameter(stores, "5.1", "LevelCap", "120", "combat");
        LoadedCode code = AlignmentTestSupport.loadedCode(List.of(
                AlignmentTestSupport.symbol("s-1", "method", "com.game.LevelValidator",
                        "LevelCap", "LevelValidator.java", 10, 30, false)));
        BusinessConceptService service = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(code),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(code)));

        // 构建历史版本 5.0：不得挂当前代码
        service.build("immortal", "5.0");
        BusinessConcept concept = stores.alignment()
                .findConceptByKey("immortal", "combat.levelcap")
                .orElseThrow();
        assertThat(stores.alignment().findMembers("immortal", concept.conceptId(), "5.0"))
                .extracting(ConceptMember::sourceType).doesNotContain("CODE");
        assertThat(stores.alignment().findMembers("immortal", concept.conceptId(), "5.0"))
                .extracting(ConceptMember::commitSha).doesNotContain("abc123");

        // 构建最新版本 5.1：挂当前代码（当前实现事实）
        service.build("immortal", "5.1");
        assertThat(stores.alignment().findMembers("immortal", concept.conceptId(), "5.1"))
                .extracting(ConceptMember::sourceType).contains("CODE");
    }

    @Test
    void sameEntityAcrossVersionsReturnsSameConceptId() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        seedParameter(stores, "5.0", "Fireball_CD", "12");
        seedParameter(stores, "5.1", "Fireball_CD", "10");

        BusinessConceptService service = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty()),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty())));
        BuildResult result = service.buildProject("immortal");

        assertThat(result.members()).isEqualTo(2);
        BusinessConcept concept = stores.alignment()
                .findConceptByKey("immortal", "combat.fireballcd")
                .orElseThrow();
        assertThat(concept.canonicalKey()).doesNotContain("param:").doesNotContain("req:");
        List<ConceptMember> allMembers = stores.alignment().findMembers("immortal", concept.conceptId(), null);
        assertThat(allMembers).extracting(ConceptMember::businessVersion)
                .containsExactlyInAnyOrder("5.0", "5.1");
    }

    @Test
    void parameterRequirementTestMembersShareSameEntityWhenNamesAlign() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        seedParameter(stores, "5.1", "Fireball_CD", "12");
        TestCaseClaim testCase = new TestCaseClaim(
                "tc-1", "immortal", "5.1", "TC-001", "Fireball_CD", "combat",
                "", "", "", "REQ-001", "JUnit", "FireballTest.java", "fireballCooldownShouldBe12",
                "FireballTest.java#fireball", KnowledgeStatus.SUPPORTED);
        KnowledgeClaimRecord requirement = new KnowledgeClaimRecord(
                "req-1", "immortal", "dv-immortal-5.1", SourceType.REQUIREMENT, Authority.PRIMARY,
                "immortal|5.1|combat|fireball_cd|rule", "Fireball_CD", "rule", "应支持 12 秒", "TEXT", null,
                "VERIFIED", null, null, null, "RULE", null, null, null);
        AlignmentTestSupport.seed(stores, "immortal", "5.1", List.of(), List.of(),
                List.of(testCase), List.of(), List.of());
        stores.multiSource().saveClaim(requirement);

        BusinessConceptService service = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty()),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty())));
        service.build("immortal", "5.1");

        BusinessConcept concept = stores.alignment()
                .findConceptByKey("immortal", "combat.fireballcd")
                .orElseThrow();
        List<ConceptMember> members = stores.alignment().findMembers("immortal", concept.conceptId(), "5.1");
        assertThat(members).extracting(ConceptMember::sourceType)
                .containsExactlyInAnyOrder("PARAMETER_TABLE", "REQUIREMENT", "TEST_CASE");
    }

    @Test
    void buildProjectKeepsHistoricalMembersAfterLatestVersionRebuild() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        seedParameter(stores, "5.0", "Cooldown", "5");
        seedParameter(stores, "5.1", "Damage", "100");

        BusinessConceptService service = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty()),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty())));
        service.buildProject("immortal");

        // 重建最新版本：历史版本（5.0）成员不得消失
        service.build("immortal", "5.1");

        BusinessConcept oldConcept = stores.alignment()
                .findConceptByKey("immortal", "combat.cooldown")
                .orElseThrow();
        assertThat(stores.alignment().findMembers("immortal", oldConcept.conceptId(), null))
                .extracting(ConceptMember::businessVersion).containsExactly("5.0");
        BusinessConcept newConcept = stores.alignment()
                .findConceptByKey("immortal", "combat.damage")
                .orElseThrow();
        assertThat(stores.alignment().findMembers("immortal", newConcept.conceptId(), null))
                .extracting(ConceptMember::businessVersion).containsExactly("5.1");
    }

    @Test
    void findBusinessVersionsSortsNumerically() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        seedParameter(stores, "5.2", "Fireball_CD", "12");
        seedParameter(stores, "5.10", "Fireball_CD", "15");
        seedParameter(stores, "5.1", "Fireball_CD", "10");

        assertThat(stores.multiSource().findBusinessVersions("immortal"))
                .containsExactly("5.1", "5.2", "5.10");
    }

    @Test
    void sameSubjectInDifferentModulesDoesNotMerge() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        seedParameter(stores, "5.1", "传播时间", "30", "auth");
        seedParameter(stores, "5.1", "传播时间", "60", "instance");

        BusinessConceptService service = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty()),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty())));
        service.build("immortal", "5.1");

        BusinessConcept authConcept = stores.alignment()
                .findConceptByKey("immortal", "auth.传播时间").orElseThrow();
        BusinessConcept instanceConcept = stores.alignment()
                .findConceptByKey("immortal", "instance.传播时间").orElseThrow();
        assertThat(authConcept.conceptId()).isNotEqualTo(instanceConcept.conceptId());
    }

    private void seedParameter(AlignmentTestSupport.Stores stores, String version, String parameter,
                               String value) {
        seedParameter(stores, version, parameter, value, "combat");
    }

    private void seedParameter(AlignmentTestSupport.Stores stores, String version, String parameter,
                               String value, String module) {
        ParameterClaim param = new ParameterClaim(
                "p-" + module + "-" + parameter + "-" + version, "immortal", version, "skills.xlsx",
                "技能参数", 1, "A1", module, parameter, value, value, "秒", null, null, 0, true,
                ParameterValueType.INTEGER,
                "immortal|" + version + "|" + module + "|" + parameter + "|value",
                "skills.xlsx#A1", KnowledgeStatus.SUPPORTED);
        AlignmentTestSupport.seed(stores, "immortal", version, List.of(param), List.of(),
                List.of(), List.of(), List.of());
    }
}
