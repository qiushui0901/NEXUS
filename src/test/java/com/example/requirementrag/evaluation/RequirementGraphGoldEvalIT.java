package com.example.requirementrag.evaluation;

import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldEvalReport;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.ScenarioMetrics;
import com.example.requirementrag.requirement.graph.RequirementGraphProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 需求语义图金标评测入口。
 *
 * <p>只在显式开启时运行：{@code -Dgold.eval=true}。
 * 默认使用规则预测器；{@code -Dgold.llm=true} 时使用 LLM 预测器。
 * 数据集路径可用 {@code -Dgold.dataset=...} 覆盖，默认 evaluation/requirement-graph-gold-v0.2/dataset.jsonl。
 */
@SpringBootTest(properties = {
        "logging.structured.format.console=",
        "management.tracing.sampling.probability=0",
        "app.rag.knowledge.bootstrap-enabled=false",
        "app.rag.auth.enabled=false"
})
@EnabledIfSystemProperty(named = "gold.eval", matches = "true")
class RequirementGraphGoldEvalIT {

    @Autowired private ObjectMapper objectMapper;
    @Autowired private ChatClient chatClient;
    @Autowired private RequirementGraphProperties graphProperties;

    @Test
    void evaluatesGoldDataset() throws Exception {
        Path dataset = Path.of(System.getProperty("gold.dataset",
                "evaluation/requirement-graph-gold-v0.2/dataset.jsonl")).toAbsolutePath().normalize();
        boolean llm = Boolean.parseBoolean(System.getProperty("gold.llm", "false"));

        RequirementGraphGoldLoader loader = new RequirementGraphGoldLoader(objectMapper);
        List<GoldCase> cases = loader.load(dataset);
        int limit = Integer.getInteger("gold.limit", 0);
        if (limit > 0 && limit < cases.size()) {
            cases = cases.subList(0, limit);
        }
        int parallelism = Integer.getInteger("gold.parallelism", 8);
        RequirementGraphGoldPredictor predictor = llm
                ? new LlmGoldPredictor(chatClient, graphProperties)
                : new RuleGoldPredictor();
        RequirementGraphGoldEvaluator evaluator = new RequirementGraphGoldEvaluator();
        GoldEvalReport report = evaluator.evaluateParallel(cases, predictor, parallelism);
        // 评测器自检：Oracle 应接近 1.0；Empty 应全 0（用于对比 LLM 到底比空预测好多少）
        GoldEvalReport oracleReport = evaluator.evaluate(cases, new OracleGoldPredictor());
        GoldEvalReport emptyReport = evaluator.evaluate(cases, new EmptyGoldPredictor());

        String markdown = render(report, oracleReport, emptyReport, llm);
        Path reportPath = Path.of("target/requirement-graph-gold-eval-report.md").toAbsolutePath().normalize();
        Files.writeString(reportPath, markdown, StandardCharsets.UTF_8);
        Path docs = Path.of("docs/reports/requirement-graph-gold-eval-" + LocalDate.now() + ".md")
                .toAbsolutePath().normalize();
        Files.createDirectories(docs.getParent());
        Files.writeString(docs, markdown, StandardCharsets.UTF_8);

        System.out.println("[GoldEval] report written to " + reportPath + " and " + docs);
        System.out.println("[GoldEval] overall relation F1=" + format(report.overall().relationF1())
                + " entity F1=" + format(report.overall().entityF1()));

        assertThat(report.totalCases()).isEqualTo(cases.size());
        assertThat(report.overall()).isNotNull();
    }

    private String render(GoldEvalReport report, GoldEvalReport oracleReport, GoldEvalReport emptyReport,
                          boolean llm) {
        StringBuilder out = new StringBuilder();
        out.append("# 需求语义图金标评测报告\n\n");
        out.append("- dataset: requirement-graph-gold (predictor=").append(llm ? "LLM" : "RULE").append(")\n");
        out.append("- totalCases: ").append(report.totalCases()).append("\n");
        out.append("- extractionCases: ").append(report.extractionCases()).append("\n");
        out.append("- retrievalTestCases: ").append(report.retrievalCases())
                .append("（不计入抽取 F1）\n");
        out.append("- goldEvidenceFieldCompletenessRate: ")
                .append(format(report.goldEvidenceFieldCompletenessRate())).append("\n");
        out.append("- predictionStatusCounts: ")
                .append(report.extras().getOrDefault("predictionStatusCounts", Map.of())).append("\n");
        out.append("- averageLatencyMs: ")
                .append(report.extras().getOrDefault("averageLatencyMs", 0)).append("\n\n");

        out.append("## 按场景\n\n");
        out.append("| 场景 | 用例 | 实体F1 | 关系F1 | ClaimF1 | 负例错误率 | 存疑召回 | 代码事实召回 | 漂移准确率 |\n");
        out.append("|---|---|---|---|---|---|---|---|---|\n");
        for (ScenarioMetrics metrics : report.scenarios()) {
            out.append('|').append(metrics.scenario())
                    .append('|').append(metrics.cases())
                    .append('|').append(format(metrics.entityF1()))
                    .append('|').append(format(metrics.relationF1()))
                    .append('|').append(format(metrics.claimF1()))
                    .append('|').append(format(metrics.negativeErrorRate()))
                    .append('|').append(format(metrics.uncertaintyRecall()))
                    .append('|').append(format(metrics.codeFactRecall()))
                    .append('|').append(format(metrics.driftDecisionAccuracy()))
                    .append("|\n");
        }
        ScenarioMetrics overall = report.overall();
        out.append("| **OVERALL** |").append(report.extractionCases())
                .append('|').append(format(overall.entityF1()))
                .append('|').append(format(overall.relationF1()))
                .append('|').append(format(overall.claimF1()))
                .append('|').append(format(overall.negativeErrorRate()))
                .append('|').append(format(overall.uncertaintyRecall()))
                .append('|').append(format(overall.codeFactRecall()))
                .append('|').append(format(overall.driftDecisionAccuracy()))
                .append("|\n\n");

        out.append("## 评测器自检（Oracle / Empty）\n\n");
        out.append("| 预测器 | 实体F1 | 关系F1 | ClaimF1 | 负例错误率 | 存疑召回 | 代码事实召回 | 漂移准确率 |\n");
        out.append("|---|---|---|---|---|---|---|---|\n");
        appendOverallRow(out, "Oracle", oracleReport);
        appendOverallRow(out, "Empty", emptyReport);
        out.append("\n> Oracle 应接近 1.0；若 Oracle 未达接近 1.0，说明评测器/匹配契约仍有问题，不能继续调模型。\n");

        out.append("\n> 统计口径：RETRIEVAL_TEST_CASE 不计入抽取 F1；REAL_WINDOW_COMPOSITE 需按 windowFamily 聚类后复核；"
                + "全部记录仍需人工复核为 GOLD_ACCEPTED 才能作为正式门禁。\n");
        return out.toString();
    }

    private void appendOverallRow(StringBuilder out, String label, GoldEvalReport report) {
        ScenarioMetrics overall = report.overall();
        out.append('|').append(label)
                .append('|').append(format(overall.entityF1()))
                .append('|').append(format(overall.relationF1()))
                .append('|').append(format(overall.claimF1()))
                .append('|').append(format(overall.negativeErrorRate()))
                .append('|').append(format(overall.uncertaintyRecall()))
                .append('|').append(format(overall.codeFactRecall()))
                .append('|').append(format(overall.driftDecisionAccuracy()))
                .append("|\n");
    }

    private String format(double value) {
        if (Double.isNaN(value)) return "N/A";
        return String.format(Locale.ROOT, "%.3f", value);
    }
}