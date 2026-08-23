package com.example.requirementrag.knowledge.multisource.alignment;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterValueType;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BuildResult;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.CodeSymbolView;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DriftItem;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DriftReport;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.LoadedCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementCodeDriftServiceTest {
    @TempDir Path tempDir;

    @Test
    void reportsAlignedDocumentDriftUnmappedAndMappedWithoutAssertion() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);

        ParameterClaim fireballParam = new ParameterClaim(
                "p-1", "immortal", "5.1", "skills.xlsx", "技能参数", 1, "A1", "",
                "Fireball", "12", "12", "秒", null, null, 0, true,
                ParameterValueType.INTEGER, "immortal|5.1||fireball|value",
                "skills.xlsx#A1", KnowledgeStatus.SUPPORTED);
        ParameterClaim damageParam = new ParameterClaim(
                "p-2", "immortal", "5.1", "skills.xlsx", "技能参数", 2, "A2", "",
                "Damage", "10", "10", "秒", null, null, 0, true,
                ParameterValueType.INTEGER, "immortal|5.1||damage|value",
                "skills.xlsx#A2", KnowledgeStatus.SUPPORTED);

        var requirementDocumentDrift = AlignmentTestSupport.requirement(
                "immortal", "5.1", "r-1", "Fireball", "10");
        var requirementAligned = AlignmentTestSupport.requirement(
                "immortal", "5.1", "r-2", "Damage", "10");
        var requirementUnmapped = AlignmentTestSupport.requirement(
                "immortal", "5.1", "r-3", "未实现功能", "8");
        var requirementMappedNoAssertion = AlignmentTestSupport.requirement(
                "immortal", "5.1", "r-4", "AlignedFeature", "12");

        AlignmentTestSupport.seed(stores, "immortal", "5.1",
                List.of(fireballParam, damageParam), List.of(), List.of(), List.of(),
                List.of(requirementDocumentDrift, requirementAligned, requirementUnmapped, requirementMappedNoAssertion));

        List<CodeSymbolView> symbols = List.of(
                AlignmentTestSupport.symbol("s-1", "method", "com.game.skill.Fireball",
                        "Fireball", "FireballSkill.java", 1, 5, false),
                AlignmentTestSupport.symbol("s-2", "method", "com.game.skill.Damage",
                        "Damage", "DamageSkill.java", 1, 5, false),
                AlignmentTestSupport.symbol("s-3", "method", "com.game.skill.AlignedFeature",
                        "AlignedFeature", "AlignedFeature.java", 1, 5, false));
        LoadedCode loaded = AlignmentTestSupport.loadedCode(symbols);
        VersionContextService versionContextService =
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(loaded));

        // 概念层：让 Fireball / Damage / AlignedFeature 需求挂上 CODE 成员
        BusinessConceptService conceptService = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(loaded),
                versionContextService);
        conceptService.build("immortal", "5.1");

        // 确定性实现证据：Fireball 与 Damage 的参数都匹配到代码符号 → READS_CONFIG
        CodeParameterAlignmentService parameterService = new CodeParameterAlignmentService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(loaded),
                versionContextService);
        parameterService.build("immortal", "5.1", "staging");

        RequirementCodeDriftService driftService = new RequirementCodeDriftService(
                stores.multiSource(), stores.alignment(), versionContextService);
        BuildResult result = driftService.build("immortal", "5.1", "staging");

        assertThat(result.drifts()).isEqualTo(4);
        DriftReport report = driftService.report("immortal", "5.1", "staging");
        assertThat(report.aligned()).isEqualTo(1);
        assertThat(report.documentDrift()).isEqualTo(1);
        assertThat(report.unmapped()).isEqualTo(1);
        assertThat(report.mappedNoAssertion()).isEqualTo(1);

        List<DriftItem> documentDrifts = stores.alignment().findDriftItems("immortal", "5.1",
                stores.alignment().findVersionContext("immortal", "5.1", "staging").orElseThrow().contextId(),
                "DOCUMENT_DRIFT");
        assertThat(documentDrifts).singleElement().satisfies(item -> {
            assertThat(item.sourceValue()).isEqualTo("10");
            assertThat(item.targetValue()).isEqualTo("12");
        });
    }

    @Test
    void mappedNameWithoutRelationIsNotAligned() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        var requirement = AlignmentTestSupport.requirement("immortal", "5.1", "r-1", "OnlyName", "10");
        AlignmentTestSupport.seed(stores, "immortal", "5.1", List.of(), List.of(),
                List.of(), List.of(), List.of(requirement));
        LoadedCode loaded = AlignmentTestSupport.loadedCode(List.of(
                AlignmentTestSupport.symbol("s-1", "method", "com.game.skill.OnlyName",
                        "OnlyName", "OnlyName.java", 1, 5, false)));
        VersionContextService versionContextService =
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(loaded));
        BusinessConceptService conceptService = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(loaded),
                versionContextService);
        conceptService.build("immortal", "5.1");

        RequirementCodeDriftService driftService = new RequirementCodeDriftService(
                stores.multiSource(), stores.alignment(), versionContextService);
        driftService.build("immortal", "5.1", "staging");

        DriftReport report = driftService.report("immortal", "5.1", "staging");
        assertThat(report.aligned()).isZero();
        assertThat(report.mappedNoAssertion()).isEqualTo(1);
    }
}
