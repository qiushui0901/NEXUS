package com.example.requirementrag.requirement.semantic;

import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationInput;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationResult;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticClaimCandidate;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticCondition;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticEntity;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticNumericFact;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequirementSemanticAnnotationValidatorTest {
    private static final String RAW = "玩家达到30级后开放成长基金，可进入成长基金玩法。冷却时间为30秒。";

    private final RequirementSemanticProperties properties = properties();
    private final RequirementSemanticAnnotationValidator validator =
            new RequirementSemanticAnnotationValidator(properties);

    private final SemanticAnnotationInput input = new SemanticAnnotationInput(
            "p1", "doc", "5.1", "file.md|parent-1|0", "parent-1", null,
            0, 0, RAW.length(),
            "file.md", 0, "成长基金", "成长基金", RAW, "hash");

    static RequirementSemanticProperties properties() {
        return new RequirementSemanticProperties(true, false, false, false, "", null,
                "requirement-semantic-v1", "v1", 12_000, 30, 30, 30, 30, 20, 30, 2,
                1_000, 1_800, 1_000_000, 400, true);
    }

    @Test
    void normalizesEnumsAndFillsNormalizedValue() {
        SemanticAnnotationResult result = new SemanticAnnotationResult(
                List.of(new SemanticEntity("成长基金", "feature", List.of("成长基金玩法"),
                        "explicit", "成长基金")),
                List.of(new SemanticCondition("玩家", "level", "gte", "30", "级", "number",
                        "unlock", "EXPLICIT", "达到30级")),
                List.of(),
                List.of(new SemanticNumericFact("玩家", "level", "30", null, "级", null,
                        "GTE", "EXPLICIT", "达到30级")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true);

        SemanticAnnotationResult validated = validator.validate(input, result);

        assertThat(validated.entities()).singleElement()
                .satisfies(entity -> assertThat(entity.certainty()).isEqualTo("EXPLICIT"));
        assertThat(validated.conditions()).singleElement()
                .satisfies(condition -> assertThat(condition.operator()).isEqualTo("GTE"));
        assertThat(validated.numericFacts()).singleElement()
                .satisfies(fact -> assertThat(fact.normalizedValue()).isEqualTo(30.0));
    }

    @Test
    void rejectsQuoteMissingFromSource() {
        SemanticAnnotationResult result = new SemanticAnnotationResult(
                List.of(new SemanticEntity("成长基金", "FEATURE", List.of(), "EXPLICIT",
                        "模型编造的句子")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), true);

        assertThatThrownBy(() -> validator.validate(input, result))
                .isInstanceOf(RequirementSemanticException.class)
                .satisfies(exception -> assertThat(((RequirementSemanticException) exception).code())
                        .isEqualTo("SEMANTIC_EVIDENCE_UNAVAILABLE"))
                .hasMessageContaining("连续子串");
    }

    @Test
    void rejectsBlankEvidenceQuote() {
        SemanticAnnotationResult result = new SemanticAnnotationResult(
                List.of(new SemanticEntity("成长基金", "FEATURE", List.of(), "EXPLICIT", "  ")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), true);

        assertThatThrownBy(() -> validator.validate(input, result))
                .isInstanceOf(RequirementSemanticException.class)
                .satisfies(exception -> assertThat(((RequirementSemanticException) exception).code())
                        .isEqualTo("SEMANTIC_EVIDENCE_UNAVAILABLE"))
                .hasMessageContaining("缺少 evidenceQuote");
    }

    @Test
    void rejectsUnknownOperator() {
        SemanticAnnotationResult result = new SemanticAnnotationResult(
                List.of(),
                List.of(new SemanticCondition("玩家", "level", "APPROXIMATELY", "30", "级",
                        "NUMBER", "", "EXPLICIT", "达到30级")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), true);

        assertThatThrownBy(() -> validator.validate(input, result))
                .isInstanceOf(RequirementSemanticException.class)
                .satisfies(exception -> assertThat(((RequirementSemanticException) exception).code())
                        .isEqualTo("SEMANTIC_SCHEMA_INVALID"))
                .hasMessageContaining("operator");
    }

    @Test
    void rejectsNonNumericValueForNumericOperator() {
        SemanticAnnotationResult result = new SemanticAnnotationResult(
                List.of(),
                List.of(new SemanticCondition("玩家", "level", "GTE", "三十级", "级",
                        "NUMBER", "", "EXPLICIT", "达到30级")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), true);

        assertThatThrownBy(() -> validator.validate(input, result))
                .isInstanceOf(RequirementSemanticException.class)
                .satisfies(exception -> assertThat(((RequirementSemanticException) exception).code())
                        .isEqualTo("SEMANTIC_NUMERIC_INVALID"));
    }

    @Test
    void rejectsBetweenWithoutRangeBounds() {
        SemanticAnnotationResult result = new SemanticAnnotationResult(
                List.of(),
                List.of(new SemanticCondition("玩家", "level", "BETWEEN", "30", "级",
                        "NUMBER", "", "EXPLICIT", "达到30级")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), true);

        assertThatThrownBy(() -> validator.validate(input, result))
                .isInstanceOf(RequirementSemanticException.class)
                .satisfies(exception -> assertThat(((RequirementSemanticException) exception).code())
                        .isEqualTo("SEMANTIC_NUMERIC_INVALID"))
                .hasMessageContaining("BETWEEN");
    }

    @Test
    void rejectsInvalidFactKey() {
        SemanticAnnotationResult result = new SemanticAnnotationResult(
                List.of(new SemanticEntity("成长基金", "FEATURE", List.of(), "EXPLICIT", "成长基金")),
                List.of(), List.of(), List.of(),
                List.of(new SemanticClaimCandidate("Growth Fund Min Level", "成长基金",
                        "UNLOCK_MIN_LEVEL", "30", "级", "EXPLICIT", "达到30级")),
                List.of(), List.of(), List.of(), true);

        assertThatThrownBy(() -> validator.validate(input, result))
                .isInstanceOf(RequirementSemanticException.class)
                .satisfies(exception -> assertThat(((RequirementSemanticException) exception).code())
                        .isEqualTo("SEMANTIC_FACT_KEY_INVALID"));
    }

    @Test
    void rejectsClaimSubjectOutsideSourceAndEntities() {
        SemanticAnnotationResult result = new SemanticAnnotationResult(
                List.of(new SemanticEntity("成长基金", "FEATURE", List.of(), "EXPLICIT", "成长基金")),
                List.of(), List.of(), List.of(),
                List.of(new SemanticClaimCandidate("growth_fund.unknown.owner", "神秘商人",
                        "OWNER", "张三", "", "INFERRED", "达到30级")),
                List.of(), List.of(), List.of(), true);

        assertThatThrownBy(() -> validator.validate(input, result))
                .isInstanceOf(RequirementSemanticException.class)
                .satisfies(exception -> assertThat(((RequirementSemanticException) exception).code())
                        .isEqualTo("SEMANTIC_SCHEMA_INVALID"))
                .hasMessageContaining("未声明的主体");
    }

    @Test
    void rejectsArrayOverConfiguredCap() {
        List<SemanticEntity> entities = new java.util.ArrayList<>();
        for (int i = 0; i <= properties.maxEntitiesPerChunk(); i++) {
            entities.add(new SemanticEntity("实体" + i, "FEATURE", List.of(), "EXPLICIT", "成长基金"));
        }
        SemanticAnnotationResult result = new SemanticAnnotationResult(
                entities, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), true);

        assertThatThrownBy(() -> validator.validate(input, result))
                .isInstanceOf(RequirementSemanticException.class)
                .satisfies(exception -> assertThat(((RequirementSemanticException) exception).code())
                        .isEqualTo("SEMANTIC_SCHEMA_INVALID"))
                .hasMessageContaining("上限");
    }

    @Test
    void deduplicatesEntitiesAndFactKeys() {
        SemanticAnnotationResult result = new SemanticAnnotationResult(
                List.of(new SemanticEntity("成长基金", "FEATURE", List.of(), "EXPLICIT", "成长基金"),
                        new SemanticEntity("成长基金", "FEATURE", List.of(), "EXPLICIT", "成长基金")),
                List.of(), List.of(), List.of(),
                List.of(new SemanticClaimCandidate("growth_fund.unlock.min_level", "成长基金",
                                "UNLOCK_MIN_LEVEL", "30", "级", "EXPLICIT", "达到30级"),
                        new SemanticClaimCandidate("growth_fund.unlock.min_level", "成长基金",
                                "UNLOCK_MIN_LEVEL", "30", "级", "EXPLICIT", "达到30级")),
                List.of(), List.of(), List.of(), true);

        SemanticAnnotationResult validated = validator.validate(input, result);

        assertThat(validated.entities()).hasSize(1);
        assertThat(validated.claims()).hasSize(1);
    }

    @Test
    void preservesMissingContextWithoutFabricatingConditions() {
        SemanticAnnotationResult result = new SemanticAnnotationResult(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("数值未公布"), List.of("生效版本"), true);

        SemanticAnnotationResult validated = validator.validate(input, result);

        assertThat(validated.conditions()).isEmpty();
        assertThat(validated.missingContext()).containsExactly("生效版本");
        assertThat(validated.uncertainties()).containsExactly("数值未公布");
    }

    @Test
    void acceptsRangeValueForBetween() {
        SemanticAnnotationResult result = new SemanticAnnotationResult(
                List.of(),
                List.of(new SemanticCondition("玩家", "level", "BETWEEN", "30~50", "级",
                        "NUMBER", "", "EXPLICIT", "达到30级")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), true);

        SemanticAnnotationResult validated = validator.validate(input, result);

        assertThat(validated.conditions()).singleElement()
                .satisfies(condition -> assertThat(condition.value()).isEqualTo("30~50"));
    }

    private final String currencyRaw = "成长基金奖励为灵玉，冷却时间为30秒。";

    private final SemanticAnnotationInput currencyInput = new SemanticAnnotationInput(
            "p1", "doc", "5.1", "file.md|parent-2|0", "parent-2", null,
            0, 0, currencyRaw.length(),
            "file.md", 0, "", "", currencyRaw, "hash");

    @Test
    void acceptsStringEqualityCondition() {
        SemanticAnnotationResult result = new SemanticAnnotationResult(
                List.of(),
                List.of(new SemanticCondition("成长基金", "reward_currency", "EQ", "灵玉",
                        "", "STRING", "", "EXPLICIT", "奖励为灵玉")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), true);

        SemanticAnnotationResult validated = validator.validate(currencyInput, result);

        assertThat(validated.conditions()).singleElement()
                .satisfies(condition -> {
                    assertThat(condition.operator()).isEqualTo("EQ");
                    assertThat(condition.value()).isEqualTo("灵玉");
                    assertThat(condition.valueType()).isEqualTo("STRING");
                });
    }

    @Test
    void acceptsEnumEqualityAndStringInequalityConditions() {
        SemanticAnnotationResult result = new SemanticAnnotationResult(
                List.of(),
                List.of(new SemanticCondition("成长基金", "currency_type", "EQ", "灵玉",
                                "", "ENUM", "", "EXPLICIT", "奖励为灵玉"),
                        new SemanticCondition("成长基金", "currency_type", "NE", "钻石",
                                "", "STRING", "", "EXPLICIT", "奖励为灵玉")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), true);

        SemanticAnnotationResult validated = validator.validate(currencyInput, result);

        assertThat(validated.conditions()).hasSize(2);
    }

    @Test
    void rejectsMismatchedNormalizedValue() {
        SemanticAnnotationResult result = new SemanticAnnotationResult(
                List.of(),
                List.of(),
                List.of(),
                List.of(new SemanticNumericFact("成长基金", "unlock_level", "30级", 999.0, "级",
                        "级", "GTE", "EXPLICIT", "冷却时间为30秒")),
                List.of(), List.of(), List.of(), List.of(), true);

        assertThatThrownBy(() -> validator.validate(currencyInput, result))
                .isInstanceOf(RequirementSemanticException.class)
                .satisfies(exception -> assertThat(((RequirementSemanticException) exception).code())
                        .isEqualTo("SEMANTIC_NUMERIC_INVALID"))
                .hasMessageContaining("不一致");
    }

    @Test
    void preservesNormalizedUnitAndServerSideNormalizedValue() {
        SemanticAnnotationResult result = new SemanticAnnotationResult(
                List.of(),
                List.of(),
                List.of(),
                List.of(new SemanticNumericFact("成长基金", "cooldown", "30秒", 30.0, "秒",
                        "s", "EQ", "EXPLICIT", "冷却时间为30秒")),
                List.of(), List.of(), List.of(), List.of(), true);

        SemanticAnnotationResult validated = validator.validate(currencyInput, result);

        assertThat(validated.numericFacts()).singleElement().satisfies(fact -> {
            assertThat(fact.normalizedUnit()).isEqualTo("s");
            assertThat(fact.unit()).isEqualTo("秒");
            assertThat(fact.normalizedValue()).isEqualTo(30.0);
        });
    }

    @Test
    void fallsBackToRawUnitWhenNormalizedUnitMissing() {
        SemanticAnnotationResult result = new SemanticAnnotationResult(
                List.of(),
                List.of(),
                List.of(),
                List.of(new SemanticNumericFact("成长基金", "cooldown", "30秒", null, "秒",
                        null, "EQ", "EXPLICIT", "冷却时间为30秒")),
                List.of(), List.of(), List.of(), List.of(), true);

        SemanticAnnotationResult validated = validator.validate(currencyInput, result);

        assertThat(validated.numericFacts()).singleElement()
                .satisfies(fact -> assertThat(fact.normalizedUnit()).isEqualTo("秒"));
    }

    @Test
    void rejectsBetweenNumericFactAsRangeMustLiveInConditions() {
        SemanticAnnotationResult result = new SemanticAnnotationResult(
                List.of(),
                List.of(),
                List.of(),
                List.of(new SemanticNumericFact("玩家", "level", "30~50", null, "级",
                        null, "BETWEEN", "EXPLICIT", "冷却时间为30秒")),
                List.of(), List.of(), List.of(), List.of(), true);

        assertThatThrownBy(() -> validator.validate(currencyInput, result))
                .isInstanceOf(RequirementSemanticException.class)
                .satisfies(exception -> assertThat(((RequirementSemanticException) exception).code())
                        .isEqualTo("SEMANTIC_NUMERIC_INVALID"))
                .hasMessageContaining("conditions");
    }

    @Test
    void parsesThousandSeparatedNumbers() {
        String raw = "成长基金最高档位奖励 6,480 灵玉。";
        SemanticAnnotationInput thousandInput = new SemanticAnnotationInput(
                "p1", "doc", "5.1", "file.md|parent-3|0", "parent-3", null,
                0, 0, raw.length(), "file.md", 0, "", "", raw, "hash");
        SemanticAnnotationResult result = new SemanticAnnotationResult(
                List.of(),
                List.of(),
                List.of(),
                List.of(new SemanticNumericFact("成长基金", "top_tier_reward", "6,480", 6480.0,
                        "灵玉", "lingyu", "EQ", "EXPLICIT", "6,480")),
                List.of(), List.of(), List.of(), List.of(), true);

        SemanticAnnotationResult validated = validator.validate(thousandInput, result);

        assertThat(validated.numericFacts()).singleElement()
                .satisfies(fact -> assertThat(fact.normalizedValue()).isEqualTo(6480.0));
    }
}
