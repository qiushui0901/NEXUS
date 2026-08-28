package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityName;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.QuestionExtractionRaw;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.SourceExtractionRaw;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.SourceFactRaw;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.SourceRelationRaw;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityExtractionValidatorTest {

    private final EntityExtractionValidator validator = new EntityExtractionValidator(
            new EntityExtractionProperties(true, "test-model", 2, 50_000, 200, 50, 100, 100, 0.7, true, 1));

    @Test
    void acceptsQuestionEntitiesWithinBudgetAndKnownSet() {
        QuestionExtractionRaw raw = new QuestionExtractionRaw(
                List.of(new EntityName("攻击力", List.of(), "ATTRIBUTE", 0.9)), "NUMERIC_VALUE", List.of("5.1"));
        QuestionExtractionRaw validated = validator.validateQuestion(raw);

        assertThat(validated.entities()).hasSize(1);
        assertThat(validated.intent()).isEqualTo("NUMERIC_VALUE");
        assertThat(validated.versions()).containsExactly("5.1");
    }

    @Test
    void rejectsEntityOverBudget() {
        QuestionExtractionRaw raw = new QuestionExtractionRaw(
                List.of(new EntityName("a", null, null, 0.5),
                        new EntityName("b", null, null, 0.5),
                        new EntityName("c", null, null, 0.5)), null, List.of());

        assertThatThrownBy(() -> validator.validateQuestion(raw))
                .isInstanceOf(EntityExtractionException.class)
                .extracting(e -> ((EntityExtractionException) e).code())
                .isEqualTo("ENTITY_BUDGET_EXCEEDED");
    }

    @Test
    void acceptsUnknownLlmEntityNameStructurallyAnalyzerDropsIt() {
        // 校验层只做结构校验；名字能否解析到真实概念由 QuestionEntityAnalyzer 决定（resolve-or-drop）
        QuestionExtractionRaw raw = new QuestionExtractionRaw(
                List.of(new EntityName("未注册实体", null, null, 0.9)), null, List.of());

        assertThat(validator.validateQuestion(raw).entities()).hasSize(1);
    }

    @Test
    void rejectsSourceFactClaimOutsideInputBatch() {
        SourceExtractionRaw raw = new SourceExtractionRaw(
                List.of(), List.of(new SourceFactRaw("x", "p", "1", null, "claim-999", 0.9)), List.of());

        assertThatThrownBy(() -> validator.validateSource(raw, Set.of("claim-1", "claim-2")))
                .isInstanceOf(EntityExtractionException.class)
                .extracting(e -> ((EntityExtractionException) e).code())
                .isEqualTo("ENTITY_CLAIM_INVALID");
    }

    @Test
    void acceptsSourceFactClaimInsideInputBatch() {
        SourceExtractionRaw raw = new SourceExtractionRaw(
                List.of(), List.of(new SourceFactRaw("x", "p", "1", null, "claim-1", 0.9)), List.of());

        assertThat(validator.validateSource(raw, Set.of("claim-1", "claim-2")).facts()).hasSize(1);
    }

    @Test
    void rejectsRelationTypeOutsideWhitelist() {
        SourceExtractionRaw raw = new SourceExtractionRaw(
                List.of(), List.of(),
                List.of(new SourceRelationRaw("a", "b", "SOMETHING_MADE_UP", 0.9)));

        assertThatThrownBy(() -> validator.validateSource(raw, Set.of()))
                .isInstanceOf(EntityExtractionException.class)
                .extracting(e -> ((EntityExtractionException) e).code())
                .isEqualTo("ENTITY_RELATION_INVALID");
    }

    @Test
    void rejectsSelectionOfUnknownCandidate() {
        assertThatThrownBy(() -> validator.validateSelection("con:fake", Set.of("con:real")))
                .isInstanceOf(EntityExtractionException.class)
                .extracting(e -> ((EntityExtractionException) e).code())
                .isEqualTo("ENTITY_UNKNOWN");
    }

    @Test
    void allowsNullSelectionForNoMatch() {
        // LLM 返回 null = 都不匹配，允许（由调用方决定 NEEDS_REVIEW）
        validator.validateSelection(null, Set.of("con:real"));
    }
}