package com.example.requirementrag.evaluation;

import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldEvalReport;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.ScenarioMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实金标数据集的评测器自检门禁（无需 LLM，普通单元测试即运行）。
 *
 * <p>用真实 dataset.jsonl 断言：
 * <ul>
 *   <li>Oracle 预测器 → 实体/关系/Claim F1 = 1.0、代码事实召回/精度 = 1.0、漂移决策准确率 = 1.0、负例错误率 = 0；</li>
 *   <li>Empty 预测器 → 实体/关系/Claim F1 = 0、代码事实召回 = 0。</li>
 * </ul>
 * 任何一条不满足都说明匹配/期望契约被改坏，CI 直接失败——这正好把 Review 里的“自检没形成质量门禁”补上。
 */
class RequirementGraphGoldDatasetSelfCheckTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RequirementGraphGoldEvaluator evaluator = new RequirementGraphGoldEvaluator();

    @Test
    void oracleReachesPerfectMetricsOnRealDataset() throws Exception {
        List<GoldCase> cases = loadDataset();
        GoldEvalReport oracleReport = evaluator.evaluate(cases, new OracleGoldPredictor());

        ScenarioMetrics overall = oracleReport.overall();
        assertThat(overall.entityF1()).as("Oracle 实体F1").isEqualTo(1.0);
        assertThat(overall.relationF1()).as("Oracle 关系F1").isEqualTo(1.0);
        assertThat(overall.claimF1()).as("Oracle ClaimF1").isEqualTo(1.0);
        assertThat(overall.codeFactRecall()).as("Oracle 代码事实召回").isEqualTo(1.0);
        assertThat(overall.codeFactPrecision()).as("Oracle 代码事实精度").isEqualTo(1.0);
        assertThat(overall.codeFactF1()).as("Oracle 代码事实F1").isEqualTo(1.0);
        assertThat(overall.driftDecisionAccuracy()).as("Oracle 漂移决策准确率").isEqualTo(1.0);
        assertThat(overall.negativeErrorRate()).as("Oracle 负例错误率").isEqualTo(0.0);
    }

    @Test
    void emptyBaselineIsZeroOnRealDataset() throws Exception {
        List<GoldCase> cases = loadDataset();
        GoldEvalReport emptyReport = evaluator.evaluate(cases, new EmptyGoldPredictor());

        ScenarioMetrics overall = emptyReport.overall();
        assertThat(overall.entityF1()).as("Empty 实体F1").isEqualTo(0.0);
        assertThat(overall.relationF1()).as("Empty 关系F1").isEqualTo(0.0);
        assertThat(overall.claimF1()).as("Empty ClaimF1").isEqualTo(0.0);
        assertThat(overall.codeFactRecall()).as("Empty 代码事实召回").isEqualTo(0.0);
    }

    private List<GoldCase> loadDataset() throws Exception {
        String[] candidates = {
                "evaluation/requirement-graph-gold-v0.2/dataset.jsonl",
                "src/test/resources/evaluation/requirement-graph-gold-v0.2/dataset.jsonl",
                "../evaluation/requirement-graph-gold-v0.2/dataset.jsonl"
        };
        Path dataset = null;
        for (String candidate : candidates) {
            Path path = Path.of(candidate).toAbsolutePath().normalize();
            if (Files.exists(path)) {
                dataset = path;
                break;
            }
        }
        assertThat(dataset).as("找不到金标数据集 dataset.jsonl").isNotNull();
        return new RequirementGraphGoldLoader(objectMapper).load(dataset);
    }
}
