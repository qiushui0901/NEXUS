package com.example.requirementrag.requirement.semantic;

import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationResult;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticClaimCandidate;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticCondition;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticEntity;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticQuestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementSemanticTextComposerTest {
    private final RequirementSemanticTextComposer composer = new RequirementSemanticTextComposer();

    private final String rawText = "玩家达到30级后开放成长基金，可进入成长基金玩法。";

    private final SemanticAnnotationResult result = new SemanticAnnotationResult(
            List.of(new SemanticEntity("成长基金", "FEATURE", List.of("成长基金玩法"), "EXPLICIT", "成长基金")),
            List.of(new SemanticCondition("玩家", "level", "GTE", "30", "级", "NUMBER",
                    "unlock", "EXPLICIT", "达到30级")),
            List.of(),
            List.of(),
            List.of(new SemanticClaimCandidate("growth_fund.unlock.min_level", "成长基金",
                    "UNLOCK_MIN_LEVEL", "30", "级", "EXPLICIT", "达到30级")),
            List.of(new SemanticQuestion("玩家多少级可以开启成长基金？", "CONDITION")),
            List.of(),
            List.of("生效版本"),
            true);

    @Test
    void composeIsDeterministicAndStructurallyStable() {
        String first = composer.compose(rawText, result);
        String second = composer.compose(rawText, result);

        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith("[原文]\n" + rawText);
        assertThat(first).contains("[主体]\n成长基金");
        assertThat(first).contains("[条件]\n玩家 level >= 30级（unlock）");
        assertThat(first).contains("[事实]\ngrowth_fund.unlock.min_level = 30级");
        assertThat(first).contains("[可能的问题]\n玩家多少级可以开启成长基金？");
        assertThat(first).contains("[缺失上下文]\n生效版本");
    }

    @Test
    void composeWithoutResultKeepsRawTextOnly() {
        String text = composer.compose(rawText, null);

        assertThat(text).isEqualTo("[原文]\n" + rawText);
    }

    @Test
    void summaryIsSingleLineAndBounded() {
        String summary = composer.summary(result);

        assertThat(summary).doesNotContain("\n");
        assertThat(summary).contains("主体：成长基金");
        assertThat(summary).contains("条件：玩家 level>=30级");
        assertThat(summary).contains("事实：growth_fund.unlock.min_level=30");
    }

    @Test
    void emptyResultRendersEmptySummary() {
        assertThat(composer.summary(SemanticAnnotationResult.empty())).isEmpty();
    }
}
