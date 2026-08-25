package com.example.requirementrag.requirement.semantic;

import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationInput;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementSemanticPromptServiceTest {
    private final RequirementSemanticPromptService service = new RequirementSemanticPromptService(
            RequirementSemanticAnnotationValidatorTest.properties());

    @Test
    void systemPromptCarriesAllControlledVocabulariesAndConstraints() {
        String prompt = service.systemPrompt();

        assertThat(prompt).contains("只返回 JSON");
        assertThat(prompt).contains("连续子串");
        assertThat(prompt).contains("missingContext");
        assertThat(prompt).contains("EXPLICIT, DERIVED, INFERRED, UNKNOWN");
        assertThat(prompt).contains("EQ, NE, GT, GTE, LT, LTE, IN, NOT_IN, BETWEEN, BEFORE, AFTER, REQUIRES, FORBIDS, UNKNOWN");
        assertThat(prompt).contains("NUMBER, STRING, BOOLEAN, ENUM, DATE, DURATION, RANGE, UNKNOWN");
        assertThat(prompt).contains("growth_fund.unlock.min_level");
        assertThat(prompt).doesNotContain("%s");
    }

    @Test
    void userPromptIncludesMetadataAndRawTextOnly() {
        SemanticAnnotationInput input = new SemanticAnnotationInput(
                "p1", "doc", "5.1", "file.md|parent-1|0", "parent-1", "window-1",
                2, 100, 160,
                "file.md", 3, "成长 / 基金", "成长基金", "玩家达到30级后开放成长基金。", "hash-1");

        String prompt = service.userPrompt(input);

        assertThat(prompt).contains("file.md");
        assertThat(prompt).contains("parent-1");
        assertThat(prompt).contains("window-1");
        assertThat(prompt).contains("成长 / 基金");
        assertThat(prompt).contains("hash-1");
        assertThat(prompt).contains("玩家达到30级后开放成长基金。");
        assertThat(prompt).contains("requirement-semantic-v1");
    }

    @Test
    void promptVersionReflectsConfiguration() {
        assertThat(service.promptVersion()).isEqualTo("requirement-semantic-v1");
    }
}
