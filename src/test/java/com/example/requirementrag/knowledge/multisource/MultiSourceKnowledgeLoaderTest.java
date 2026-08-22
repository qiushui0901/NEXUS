package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterValueType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MultiSourceKnowledgeLoaderTest {
    private final ParameterTableLoader parameterLoader = new ParameterTableLoader();
    private final DoubtClaimParser doubtParser = new DoubtClaimParser();

    @Test
    void parsesParameterRowWithAliasesUnitsAndBounds() {
        var layout = parameterLoader.parseHeaders(List.of("模块", "参数", "值", "单位", "下限", "上限", "版本", "说明"));
        Map<String, String> row = Map.of(
                "0", "订单", "1", "传播时间", "2", "5分钟", "3", "分钟",
                "4", "0", "5", "5", "6", "5.1", "7", "含边界");
        List<ParameterClaim> claims = parameterLoader.parse(layout, List.of(row), "fengshen", "5.0",
                "参数表.xlsx", "5.1参数");

        assertThat(claims).singleElement().satisfies(claim -> {
            assertThat(claim.module()).isEqualTo("订单");
            assertThat(claim.parameter()).isEqualTo("传播时间");
            assertThat(claim.valueType()).isEqualTo(ParameterValueType.DURATION);
            assertThat(claim.normalizedValue()).isEqualTo("5分钟");
            assertThat(claim.unit()).isEqualTo("分钟");
            assertThat(claim.minValue()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(claim.maxValue()).isEqualByComparingTo(BigDecimal.valueOf(5));
            assertThat(claim.inclusiveBoundary()).isTrue();
            assertThat(claim.version()).isEqualTo("5.1");
            assertThat(claim.factKey()).contains("订单|传播时间");
            assertThat(claim.evidenceLocation()).isEqualTo("5.1参数!2");
        });
    }

    @Test
    void recognizesPercentageAndBooleanTypes() {
        var layout = parameterLoader.parseHeaders(List.of("参数", "值"));
        List<ParameterClaim> claims = parameterLoader.parse(layout,
                List.of(Map.of("0", "费率", "1", "12.5%"), Map.of("0", "启用", "1", "是")),
                "p", "1.0", "t.xlsx", "表");

        assertThat(claims.get(0).valueType()).isEqualTo(ParameterValueType.PERCENTAGE);
        assertThat(claims.get(0).normalizedValue()).isEqualTo("12.5");
        assertThat(claims.get(1).valueType()).isEqualTo(ParameterValueType.BOOLEAN);
    }

    @Test
    void parsesDoubtClaimWithStatusAndLocation() {
        DoubtClaim doubt = doubtParser.parse(
                Map.of("问题", "权限撤销传播时间未确认", "状态", "OPEN", "负责人", "张三", "严重级别", "高"),
                "fengshen", "5.1", "5.1存疑", 1);

        assertThat(doubt.question()).isEqualTo("权限撤销传播时间未确认");
        assertThat(doubt.status()).isEqualTo(DoubtStatus.OPEN);
        assertThat(doubt.owner()).isEqualTo("张三");
        assertThat(doubt.evidenceLocation()).isEqualTo("5.1存疑!2");
    }

    @Test
    void rejectsRowWithoutParameterName() {
        var layout = parameterLoader.parseHeaders(List.of("模块", "参数", "值"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                parameterLoader.parse(layout, List.of(Map.of("0", "订单", "1", "  ", "2", "1")),
                        "p", "1.0", "t.xlsx", "表"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}