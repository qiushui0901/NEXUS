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
    void reportsAlignedDocumentDriftAndUnmapped() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);

        ParameterClaim fireballParam = new ParameterClaim(
                "p-1", "immortal", "5.1", "skills.xlsx", "技能参数", 1, "A1", "",
                "Fireball", "12", "12", "秒", null, null, 0, true,
                ParameterValueType.INTEGER, "immortal|5.1||fireball|value",
                "skills.xlsx#A1", KnowledgeStatus.SUPPORTED);

        var requirementDocumentDrift = AlignmentTestSupport.requirement(
                "immortal", "5.1", "r-1", "Fireball", "10");
        var requirementUnmapped = AlignmentTestSupport.requirement(
                "immortal", "5.1", "r-2", "未实现功能", "8");
        var requirementAligned = AlignmentTestSupport.requirement(
                "immortal", "5.1", "r-3", "AlignedFeature", "12");

        AlignmentTestSupport.seed(stores, "immortal", "5.1", List.of(fireballParam), List.of(),
                List.of(), List.of(), List.of(requirementDocumentDrift, requirementUnmapped, requirementAligned));

        List<CodeSymbolView> symbols = List.of(
                AlignmentTestSupport.symbol("s-1", "method", "com.game.skill.Fireball",
                        "Fireball", "FireballSkill.java", 1, 5, false),
                AlignmentTestSupport.symbol("s-2", "method", "com.game.skill.AlignedFeature",
                        "AlignedFeature", "AlignedFeature.java", 1, 5, false));
        LoadedCode loaded = AlignmentTestSupport.loadedCode(symbols);
        VersionContextService versionContextService =
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(loaded));

        // 先建概念层，让 Fireball / AlignedFeature 需求挂上 CODE 成员
        BusinessConceptService conceptService = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(loaded));
        conceptService.build("immortal", "5.1");

        RequirementCodeDriftService driftService = new RequirementCodeDriftService(
                stores.multiSource(), stores.alignment(), versionContextService);
        BuildResult result = driftService.build("immortal", "5.1", "staging");

        assertThat(result.drifts()).isEqualTo(3);
        DriftReport report = driftService.report("immortal", "5.1", "staging");
        assertThat(report.aligned()).isEqualTo(1);
        assertThat(report.documentDrift()).isEqualTo(1);
        assertThat(report.unmapped()).isEqualTo(1);

        List<DriftItem> documentDrifts = stores.alignment().findDriftItems("immortal", "5.1", "DOCUMENT_DRIFT");
        assertThat(documentDrifts).singleElement().satisfies(item -> {
            assertThat(item.sourceValue()).isEqualTo("10");
            assertThat(item.targetValue()).isEqualTo("12");
        });
    }
}
