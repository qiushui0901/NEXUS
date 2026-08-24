package com.example.requirementrag.evaluation;

import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldEntity;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldRelation;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.Prediction;
import com.example.requirementrag.requirement.graph.RequirementGraphException;
import com.example.requirementrag.requirement.graph.RequirementGraphExtractionService;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractedEntity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractedRelation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractionInput;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 生产抽取链路预测器：验证它确实走真实 {@code RequirementGraphExtractionService.extract}，
 * 并把抽取结果合并为 {@link Prediction}（而非像 Prompt 基准那样直接调 LLM）。
 */
class ProductionGraphPredictorTest {

    @Test
    void mergesSingleWindowExtractionIntoPrediction() {
        RequirementGraphExtractionService extractionService = mock(RequirementGraphExtractionService.class);
        when(extractionService.extract(any(ExtractionInput.class))).thenReturn(new ExtractionResult(
                List.of(
                        new ExtractedEntity("e1", "FEATURE", "成长基金", List.of(), "desc",
                                List.of("成长基金是长期玩法"), 0.9),
                        new ExtractedEntity("e2", "RESOURCE", "灵玉", List.of(), "desc",
                                List.of("灵玉奖励"), 0.9)),
                List.of(new ExtractedRelation("e1", "REWARDS", "e2", "奖励灵玉",
                        List.of("成长基金是长期玩法"), 0.8)),
                List.of()));
        ProductionGraphPredictor predictor = new ProductionGraphPredictor(extractionService);

        GoldCase gold = new GoldCase("c1", "SINGLE_UNIT", "成长基金是长期玩法，奖励灵玉。",
                List.of(new GoldEntity("growth-fund", "FEATURE", "成长基金", List.of()),
                        new GoldEntity("lingyu", "RESOURCE", "灵玉", List.of())),
                List.of(new GoldRelation("growth-fund", "REWARDS", "lingyu", List.of("ev-1"))),
                List.of(), List.of(), List.of(), 0, 0);
        Prediction prediction = predictor.predict(gold);

        assertThat(prediction.entities().stream()
                .map(RequirementGraphGoldModels.PredictedEntity::name))
                .containsExactlyInAnyOrder("成长基金", "灵玉");
        assertThat(prediction.relations()).containsExactly(
                new RequirementGraphGoldModels.PredictedRelation("成长基金", "灵玉", "REWARDS"));
        assertThat(prediction.uncertainties()).isEmpty();
        assertThat(prediction.status()).isEqualTo(RequirementGraphGoldModels.PredictionStatus.SUCCESS);
        assertThat(prediction.retryCount()).isZero();
    }

    @Test
    void reportsFailureWhenExtractionChainThrows() {
        RequirementGraphExtractionService extractionService = mock(RequirementGraphExtractionService.class);
        when(extractionService.extract(any(ExtractionInput.class)))
                .thenThrow(new RequirementGraphException("GRAPH_MODEL_UNAVAILABLE", "model down"));
        ProductionGraphPredictor predictor = new ProductionGraphPredictor(extractionService);

        GoldCase gold = new GoldCase("c1", "SINGLE_UNIT", "成长基金是长期玩法，奖励灵玉。",
                List.of(new GoldEntity("growth-fund", "FEATURE", "成长基金", List.of())),
                List.of(), List.of(), List.of(), List.of(), 0, 0);
        Prediction prediction = predictor.predict(gold);

        assertThat(prediction.status()).isEqualTo(RequirementGraphGoldModels.PredictionStatus.FAILURE);
        assertThat(prediction.errorCode()).isEqualTo("GRAPH_MODEL_UNAVAILABLE");
        assertThat(prediction.entities()).isEmpty();
    }

    @Test
    void keepsSuccessfulResultsWhenSomeWindowsFail() {
        RequirementGraphExtractionService extractionService = mock(RequirementGraphExtractionService.class);
        when(extractionService.extract(any(ExtractionInput.class)))
                .thenThrow(new RequirementGraphException("GRAPH_MODEL_UNAVAILABLE", "model down"))
                .thenReturn(new ExtractionResult(
                        List.of(new ExtractedEntity("e1", "FEATURE", "成长基金", List.of(),
                                "desc", List.of("成长基金是长期玩法"), 0.9)),
                        List.of(), List.of()));
        ProductionGraphPredictor predictor = new ProductionGraphPredictor(extractionService);

        GoldCase gold = new GoldCase("c1", "REAL_WINDOW_COMPOSITE", "成长基金是长期玩法，奖励灵玉。",
                List.of(
                        new RequirementGraphGoldModels.GoldWindow("w1", 0, "p1", 1, "f.html", 0, 10, "h1", "成长基金"),
                        new RequirementGraphGoldModels.GoldWindow("w2", 1, "p1", 1, "f.html", 10, 20, "h2", "奖励灵玉")),
                List.of(new GoldEntity("growth-fund", "FEATURE", "成长基金", List.of())),
                List.of(), List.of(), List.of(), List.of(), 0, 0);
        Prediction prediction = predictor.predict(gold);

        assertThat(prediction.status()).isEqualTo(RequirementGraphGoldModels.PredictionStatus.FAILURE);
        assertThat(prediction.errorCode()).isEqualTo("GRAPH_MODEL_UNAVAILABLE");
        assertThat(prediction.entities().stream()
                .map(RequirementGraphGoldModels.PredictedEntity::name))
                .containsExactly("成长基金");
    }
}