package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestCaseClaim;
import com.example.requirementrag.knowledge.multisource.XlsxTableReader.XlsxSheet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImmortalLoadersTest {

    @Test
    void xlsxTestCaseLoaderMapsGroupModuleStepsExpected() {
        XlsxSheet sheet = new XlsxSheet("英雄",
                List.of("分组", "模块", "操作步骤", "预期结果"),
                List.of(
                        Map.of("0", "英雄升级", "1", "英雄", "2", "点击升级", "3", "等级+1"),
                        Map.of("0", "英雄升星", "1", "英雄", "2", "消耗碎片", "3", "星级+1"),
                        Map.of("0", "", "1", "", "2", "", "3", "")
                ));
        XlsxTestCaseLoader loader = new XlsxTestCaseLoader();

        List<TestCaseClaim> claims = loader.parseSheet(sheet, "fengshen", "5.1", "英雄.xlsx");

        assertThat(claims).hasSize(2);
        TestCaseClaim first = claims.get(0);
        assertThat(first.module()).isEqualTo("英雄");
        assertThat(first.steps()).isEqualTo("点击升级");
        assertThat(first.expectedResult()).isEqualTo("等级+1");
        assertThat(first.testCaseId()).startsWith("英雄升级-英雄-");
        assertThat(first.evidenceLocation()).contains("英雄.xlsx#英雄!2");
        assertThat(first.claimId()).startsWith("tc:");
        assertThat(claims.get(1).steps()).isEqualTo("消耗碎片");
    }

    @Test
    void configTableLoaderTurnsEachColumnIntoParameterClaim() {
        XlsxSheet sheet = new XlsxSheet("HeroConfig",
                List.of("id", "name", "value"),
                List.of(
                        Map.of("0", "1", "1", "火球术", "2", "10"),
                        Map.of("0", "2", "1", "冰盾", "2", "20")
                ));
        ConfigTableLoader loader = new ConfigTableLoader();

        List<ParameterClaim> claims = loader.parseSheet(sheet, "fengshen", "5.1", "ImmortalHero.xlsx");

        assertThat(claims).hasSize(6);
        ParameterClaim valueClaim = claims.stream()
                .filter(claim -> claim.parameter().equals("value") && claim.rawValue().equals("10"))
                .findFirst().orElseThrow();
        assertThat(valueClaim.module()).isEqualTo("HeroConfig");
        assertThat(valueClaim.rowNumber()).isEqualTo(2);
        assertThat(valueClaim.columnRange()).isEqualTo("C2");
        assertThat(valueClaim.evidenceLocation()).isEqualTo("ImmortalHero.xlsx#HeroConfig!2:C");
        assertThat(valueClaim.valueType().name()).isEqualTo("INTEGER");
        assertThat(valueClaim.factKey()).isEqualTo("fengshen|5.1|heroconfig|value");
    }

    @Test
    void doubtParserAcceptsJinJinRenAndChanPinDaYi() {
        DoubtClaim doubt = new DoubtClaimParser().parse(
                Map.of("问题", "冷却时间是否合理", "跟进人", "小明", "产品答疑", "下版本调整为10秒", "备注", "待定"),
                "fengshen", "5.1", "存疑", 1);

        assertThat(doubt.owner()).isEqualTo("小明");
        assertThat(doubt.answer()).isEqualTo("下版本调整为10秒");
        assertThat(doubt.question()).isEqualTo("冷却时间是否合理");
    }
}