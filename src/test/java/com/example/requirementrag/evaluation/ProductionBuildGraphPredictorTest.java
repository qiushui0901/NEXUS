package com.example.requirementrag.evaluation;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldEntity;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldRelation;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.Prediction;
import com.example.requirementrag.requirement.graph.RequirementGraphBuildService;
import com.example.requirementrag.requirement.graph.RequirementGraphException;
import com.example.requirementrag.requirement.graph.RequirementGraphExtractionService;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractedEntity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractedRelation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractionInput;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractionResult;
import com.example.requirementrag.requirement.graph.RequirementGraphProperties;
import com.example.requirementrag.requirement.graph.SQLiteRequirementGraphStore;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 完整生产构建链路预测器：验证它确实通过真实 {@code RequirementGraphBuildService.build}
 * 走窗口规划→抽取→跨窗口合并→证据→SQLite 持久化，并把快照结果还原为 {@link Prediction}。
 */
class ProductionBuildGraphPredictorTest {

    @TempDir
    Path tempDir;

    @Test
    void runsRealBuildServiceAndReturnsSnapshotGraph() {
        RequirementGraphExtractionService extractionService = mock(RequirementGraphExtractionService.class);
        when(extractionService.extract(any(ExtractionInput.class))).thenReturn(new ExtractionResult(
                List.of(
                        new ExtractedEntity("e1", "FEATURE", "成长基金", List.of(), "desc",
                                List.of("成长基金是长期玩法"), 0.9),
                        new ExtractedEntity("e2", "MODULE", "库存", List.of(), "desc",
                                List.of("影响库存"), 0.8)),
                List.of(new ExtractedRelation("e1", "AFFECTS_MODULE", "e2", "成长基金影响库存",
                        List.of("成长基金是长期玩法"), 0.8)),
                List.of()));
        RequirementGraphProperties properties = new RequirementGraphProperties(
                true, true, true, tempDir.resolve("unused.db").toString(), 20, 30, 20_000, 2, 40,
                "model", "v1");
        ProductionBuildGraphPredictor predictor = buildPredictor(extractionService, properties);

        GoldCase gold = new GoldCase("c1", "SINGLE_UNIT", "成长基金是长期玩法，影响库存。",
                List.of(new GoldEntity("growth-fund", "FEATURE", "成长基金", List.of()),
                        new GoldEntity("inventory", "MODULE", "库存", List.of())),
                List.of(new GoldRelation("growth-fund", "AFFECTS_MODULE", "inventory", List.of("ev-1"))),
                List.of(), List.of(), List.of(), 0, 0);
        Prediction prediction = predictor.predict(gold);

        assertThat(prediction.status()).isEqualTo(RequirementGraphGoldModels.PredictionStatus.SUCCESS);
        assertThat(prediction.entities()).containsExactlyInAnyOrder("成长基金", "库存");
        assertThat(prediction.relations()).containsExactly(
                new RequirementGraphGoldModels.PredictedRelation("成长基金", "库存", "AFFECTS_MODULE"));
    }

    @Test
    void reportsFailureWhenBuildChainThrows() {
        RequirementGraphExtractionService extractionService = mock(RequirementGraphExtractionService.class);
        when(extractionService.extract(any(ExtractionInput.class)))
                .thenThrow(new RequirementGraphException("GRAPH_MODEL_UNAVAILABLE", "model down"));
        RequirementGraphProperties properties = new RequirementGraphProperties(
                true, true, true, tempDir.resolve("unused2.db").toString(), 20, 30, 20_000, 2, 40,
                "model", "v1");
        ProductionBuildGraphPredictor predictor = buildPredictor(extractionService, properties);

        GoldCase gold = new GoldCase("c1", "SINGLE_UNIT", "成长基金是长期玩法，影响库存。",
                List.of(new GoldEntity("growth-fund", "FEATURE", "成长基金", List.of())),
                List.of(), List.of(), List.of(), List.of(), 0, 0);
        Prediction prediction = predictor.predict(gold);

        assertThat(prediction.status()).isEqualTo(RequirementGraphGoldModels.PredictionStatus.FAILURE);
        assertThat(prediction.errorCode()).isNotBlank();
    }

    private ProductionBuildGraphPredictor buildPredictor(RequirementGraphExtractionService extractionService,
                                                         RequirementGraphProperties properties) {
        Path db = tempDir.resolve("graph-" + System.nanoTime() + ".db");
        RequirementGraphProperties localProperties = ProductionBuildGraphPredictor.withDatabasePath(properties, db.toString());
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(new ObjectMapper(), localProperties);
        ProductionBuildGraphPredictor.MapRequirementSnapshotRepository snapshots =
                new ProductionBuildGraphPredictor.MapRequirementSnapshotRepository(tempDir);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        when(registry.resolveRequirementCollection(anyString())).thenReturn("requirements_gold");
        RequirementGraphBuildService buildService = new RequirementGraphBuildService(
                store, extractionService, snapshots, mock(QdrantHybridStore.class), registry, localProperties);
        return new ProductionBuildGraphPredictor(buildService, store, snapshots);
    }
}