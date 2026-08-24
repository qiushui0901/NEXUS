package com.example.requirementrag.evaluation;

import com.example.requirementrag.evaluation.RequirementGraphGoldLoader.GoldLoadMode;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 金标加载器：验证 EXPLORATORY / FORMAL 模式、显式 decision 要求与结构完整性校验。
 */
class RequirementGraphGoldLoaderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RequirementGraphGoldLoader loader = new RequirementGraphGoldLoader(objectMapper);

    @TempDir
    Path tempDir;

    @Test
    void formalModeRejectsNonAcceptedRecords() throws Exception {
        Path dataset = writeDataset("{"
                + "\"caseId\":\"c1\",\"scenario\":\"SINGLE_UNIT\",\"projectId\":\"p\",\"documentId\":\"d\",\"requirementVersion\":\"1.0\","
                + "\"input\":{\"text\":\"成长基金奖励灵玉。\"},"
                + "\"gold\":{\"entities\":[{\"id\":\"e1\",\"type\":\"FEATURE\",\"canonicalName\":\"成长基金\",\"aliases\":[]}],"
                + "\"relations\":[],\"claims\":[],\"uncertainties\":[],\"evidence\":[],\"codeFacts\":[]},"
                + "\"annotation\":{\"status\":\"HUMAN_REVIEW_REQUIRED\"}}");
        List<GoldCase> exploratory = loader.load(dataset, GoldLoadMode.EXPLORATORY);
        assertThat(exploratory).hasSize(1);
        assertThat(exploratory.get(0).annotationStatus()).isEqualTo("HUMAN_REVIEW_REQUIRED");

        assertThatThrownBy(() -> loader.load(dataset, GoldLoadMode.FORMAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只允许 GOLD_ACCEPTED");
    }

    @Test
    void formalModeRequiresExplicitDecisionForDriftScenario() throws Exception {
        Path dataset = writeDataset("{"
                + "\"caseId\":\"c1\",\"scenario\":\"DOCUMENT_DRIFT_REVIEW\",\"projectId\":\"p\",\"documentId\":\"d\",\"requirementVersion\":\"1.0\","
                + "\"input\":{\"text\":\"需求建议 POST /growth-fund/claim；代码注释说走通用任务接口。\"},"
                + "\"gold\":{\"entities\":[{\"id\":\"e1\",\"type\":\"FEATURE\",\"canonicalName\":\"成长基金\",\"aliases\":[]}],"
                + "\"relations\":[],\"claims\":[],\"uncertainties\":[],"
                + "\"evidence\":[{\"evidenceId\":\"ev-1\",\"items\":[{\"sourceType\":\"CODE_SOURCE\",\"sourceFile\":\"f.java\",\"quote\":\"quote\"}]}],"
                + "\"codeFacts\":[]},"
                + "\"annotation\":{\"status\":\"GOLD_ACCEPTED\"}}");
        // FORMAL 下漂移用例缺少显式 decision -> 抛错
        assertThatThrownBy(() -> loader.load(dataset, GoldLoadMode.FORMAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("显式 decision");
        // EXPLORATORY 下允许从 scenario 推导
        List<GoldCase> cases = loader.load(dataset, GoldLoadMode.EXPLORATORY);
        assertThat(cases.get(0).decision().type()).isEqualTo("DOCUMENT_DRIFT");
    }

    @Test
    void rejectsMissingEvidenceReferences() throws Exception {
        Path dataset = writeDataset("{"
                + "\"caseId\":\"c1\",\"scenario\":\"SINGLE_UNIT\",\"projectId\":\"p\",\"documentId\":\"d\",\"requirementVersion\":\"1.0\","
                + "\"input\":{\"text\":\"成长基金奖励灵玉。\"},"
                + "\"gold\":{\"entities\":[],\"relations\":[],"
                + "\"claims\":[{\"factKey\":\"a.b\",\"value\":\"v\",\"certainty\":\"SUPPORTED\",\"evidenceIds\":[\"ev-missing\"]}],"
                + "\"uncertainties\":[],\"evidence\":[],\"codeFacts\":[]},"
                + "\"annotation\":{\"status\":\"HUMAN_REVIEW_REQUIRED\"}}");
        assertThatThrownBy(() -> loader.load(dataset, GoldLoadMode.EXPLORATORY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在的 evidenceId");
    }

    @Test
    void rejectsDuplicateCaseIds() throws Exception {
        Path dataset = writeDataset("{"
                + "\"caseId\":\"c1\",\"scenario\":\"SINGLE_UNIT\",\"input\":{\"text\":\"a\"},\"gold\":{\"entities\":[],\"relations\":[],\"claims\":[],\"uncertainties\":[],\"evidence\":[],\"codeFacts\":[]},\"annotation\":{\"status\":\"HUMAN_REVIEW_REQUIRED\"}}\n"
                + "{\"caseId\":\"c1\",\"scenario\":\"SINGLE_UNIT\",\"input\":{\"text\":\"b\"},\"gold\":{\"entities\":[],\"relations\":[],\"claims\":[],\"uncertainties\":[],\"evidence\":[],\"codeFacts\":[]},\"annotation\":{\"status\":\"HUMAN_REVIEW_REQUIRED\"}}");
        assertThatThrownBy(() -> loader.load(dataset, GoldLoadMode.EXPLORATORY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("caseId 重复");
    }

    private Path writeDataset(String content) throws Exception {
        Path path = tempDir.resolve("dataset-" + System.nanoTime() + ".jsonl");
        Files.writeString(path, content);
        return path;
    }
}