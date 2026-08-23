package com.example.requirementrag.knowledge.multisource.alignment;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterValueType;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.AlignmentRelation;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BuildResult;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.CodeSymbolView;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.LoadedCode;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DriftItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CodeParameterAlignmentServiceTest {
    @TempDir Path tempDir;

    @Test
    void buildsReadsConfigFromParameterToCodeSymbol() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        ParameterClaim param = new ParameterClaim(
                "p-1", "immortal", "5.1", "skills.xlsx", "技能参数", 1, "A1", "combat",
                "Fireball_CD", "12", "12", "秒", null, null, 0, true,
                ParameterValueType.INTEGER, "immortal|5.1|combat|fireball_cd|value",
                "skills.xlsx#A1", KnowledgeStatus.SUPPORTED);
        AlignmentTestSupport.seed(stores, "immortal", "5.1", List.of(param), List.of(),
                List.of(), List.of(), List.of());

        List<CodeSymbolView> symbols = List.of(
                AlignmentTestSupport.symbol("s-1", "method", "com.game.skill.resolveFireballCd",
                        "Fireball_CD", "FireballSkill.java", 1, 5, false));
        LoadedCode loaded = AlignmentTestSupport.loadedCode(symbols);
        CodeParameterAlignmentService service = new CodeParameterAlignmentService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(loaded),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(loaded)));

        BuildResult result = service.build("immortal", "5.1", "staging");

        assertThat(result.relations()).isPositive();
        List<AlignmentRelation> relations = service.relations("immortal", "5.1", "READS_CONFIG");
        assertThat(relations).singleElement().satisfies(relation -> {
            assertThat(relation.sourceClaimId()).isEqualTo("p-1");
            assertThat(relation.targetExternalId()).isEqualTo("s-1");
            assertThat(relation.matchMethod()).isEqualTo("NORMALIZED_NAME_EXACT");
        });
    }

    @Test
    void emitsConfigDriftWhenCodeValueProviderReportsDifferentValue() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        ParameterClaim param = new ParameterClaim(
                "p-1", "immortal", "5.1", "skills.xlsx", "技能参数", 1, "A1", "combat",
                "Fireball_CD", "10", "10", "秒", null, null, 0, true,
                ParameterValueType.INTEGER, "immortal|5.1|combat|fireball_cd|value",
                "skills.xlsx#A1", KnowledgeStatus.SUPPORTED);
        AlignmentTestSupport.seed(stores, "immortal", "5.1", List.of(param), List.of(),
                List.of(), List.of(), List.of());

        CodeSymbolView symbol = AlignmentTestSupport.symbol(
                "s-1", "method", "com.game.skill.resolveFireballCd", "Fireball_CD",
                "FireballSkill.java", 1, 5, false);
        LoadedCode loaded = AlignmentTestSupport.loadedCode(List.of(symbol));
        VersionContextService versionContextService =
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(loaded));
        CodeParameterAlignmentService service = new CodeParameterAlignmentService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(loaded),
                versionContextService);
        service.withValueProvider(codeSymbol -> Optional.of(
                new CodeParameterAlignmentService.CodeValueProvider.CodeValue("12", "秒")));

        BuildResult result = service.build("immortal", "5.1", "staging");

        assertThat(result.drifts()).isPositive();
        List<DriftItem> drifts = stores.alignment().findDriftItems("immortal", "5.1", "CONFIG_DRIFT");
        assertThat(drifts).singleElement().satisfies(item -> {
            assertThat(item.sourceValue()).isEqualTo("10");
            assertThat(item.targetValue()).isEqualTo("12");
        });
    }
}