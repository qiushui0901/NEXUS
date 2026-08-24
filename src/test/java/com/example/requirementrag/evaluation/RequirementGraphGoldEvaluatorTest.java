package com.example.requirementrag.evaluation;

import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldClaim;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldEntity;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldRelation;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldUncertainty;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldEvalReport;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictedClaim;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictedRelation;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.Prediction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementGraphGoldEvaluatorTest {

    private final RequirementGraphGoldEvaluator evaluator = new RequirementGraphGoldEvaluator();

    @Test
    void computesEntityRelationAndClaimMetrics() {
        GoldCase gold = new GoldCase("c1", "SINGLE_UNIT", "成长基金奖励灵玉。",
                List.of(new GoldEntity("growth-fund", "FEATURE", "成长基金", List.of()),
                        new GoldEntity("lingyu", "RESOURCE", "灵玉", List.of())),
                List.of(new GoldRelation("growth-fund", "REWARDS", "lingyu", List.of("ev-1"))),
                List.of(new GoldClaim("growth_fund.reward_currency", "灵玉", "SUPPORTED", List.of("ev-1"))),
                List.of(), List.of(), 0, 0);

        RequirementGraphGoldPredictor perfect = c -> new Prediction(Set.of("成长基金", "灵玉"),
                List.of(new PredictedRelation("成长基金", "灵玉", "REWARDS")),
                List.of(new PredictedClaim("growth_fund.reward_currency", "灵玉")),
                List.of());

        GoldEvalReport report = evaluator.evaluate(List.of(gold), perfect);

        assertThat(report.overall().entityRecall()).isEqualTo(1.0);
        assertThat(report.overall().entityPrecision()).isEqualTo(1.0);
        assertThat(report.overall().relationRecall()).isEqualTo(1.0);
        assertThat(report.overall().relationPrecision()).isEqualTo(1.0);
        assertThat(report.overall().claimRecall()).isEqualTo(1.0);
        assertThat(report.overall().claimPrecision()).isEqualTo(1.0);
    }

    @Test
    void negativeScenarioDoesNotCountConfirmedClaimsAsSuccess() {
        GoldCase doubt = new GoldCase("d1", "DOUBT_NEGATIVE", "成长基金是否支持一键领取？",
                List.of(new GoldEntity("growth-fund", "FEATURE", "成长基金", List.of())),
                List.of(), List.of(),
                List.of(new GoldUncertainty("d1", "OPEN", "INTERACTION", "成长基金是否支持一键领取？")),
                List.of(), 0, 0);

        RequirementGraphGoldPredictor correct = c -> new Prediction(Set.of("成长基金"),
                List.of(), List.of(), List.of("成长基金是否支持一键领取？"));

        GoldEvalReport report = evaluator.evaluate(List.of(doubt), correct);

        assertThat(report.overall().negativeErrorRate()).isEqualTo(0.0);
        assertThat(report.overall().uncertaintyRecall()).isEqualTo(1.0);

        RequirementGraphGoldPredictor wrong = c -> new Prediction(Set.of(),
                List.of(new PredictedRelation("成长基金", "灵玉", "REWARDS")),
                List.of(new PredictedClaim("x.y", "真值")), List.of());
        GoldEvalReport wrongReport = evaluator.evaluate(List.of(doubt), wrong);
        assertThat(wrongReport.overall().negativeErrorRate()).isEqualTo(1.0);
    }
}