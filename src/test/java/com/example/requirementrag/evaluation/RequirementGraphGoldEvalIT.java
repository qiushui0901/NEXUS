package com.example.requirementrag.evaluation;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldEvalReport;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.ScenarioMetrics;
import com.example.requirementrag.requirement.graph.RequirementGraphBuildService;
import com.example.requirementrag.requirement.graph.RequirementGraphExtractionService;
import com.example.requirementrag.requirement.graph.RequirementGraphProperties;
import com.example.requirementrag.requirement.graph.SQLiteRequirementGraphStore;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 需求语义图金标评测入口。
 *
 * <p>只在显式开启时运行：{@code -Dgold.eval=true}。
 * 默认使用规则预测器；{@code -Dgold.llm=true} 时使用 LLM 预测器。
 * 数据集路径可用 {@code -Dgold.dataset=...} 覆盖，默认 evaluation/requirement-graph-gold-v0.2/dataset.jsonl。
 *
 * <p>本入口同时作为评测器质量门禁：Oracle 必须接近 1.0、Empty 必须全 0，
 * 否则说明匹配/期望契约被改坏，直接失败。
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
    @Autowired private RagProperties ragProperties;
    @Autowired private RequirementGraphProperties graphProperties;

    @Test
    void evaluatesGoldDataset() throws Exception {
        Path dataset = Path.of(System.getProperty("gold.dataset",
                "evaluation/requirement-graph-gold-v0.2/dataset.jsonl")).toAbsolutePath().normalize();
        // 评测入口必须显式选择预测器，禁止默默使用规则基线，避免报告被误读为生产链路能力。
        String predictorName = System.getProperty("gold.predictor");
        if (predictorName == null || predictorName.isBlank()) {
            throw new IllegalStateException(
                    "请显式指定 -Dgold.predictor=rule|llm|production|build，评测入口不再默认使用 RuleGoldPredictor");
        }
        String normalizedPredictor = predictorName.trim().toLowerCase(Locale.ROOT);
        boolean llm = "llm".equals(normalizedPredictor);
        boolean production = "production".equals(normalizedPredictor);
        boolean build = "build".equals(normalizedPredictor);
        boolean rule = "rule".equals(normalizedPredictor);
        if (!llm && !production && !build && !rule) {
            throw new IllegalStateException("未知 gold.predictor=" + predictorName + "，可选 rule|llm|production|build");
        }

        RequirementGraphGoldLoader loader = new RequirementGraphGoldLoader(objectMapper);
        boolean formal = Boolean.parseBoolean(System.getProperty("gold.formal", "false"));
        RequirementGraphGoldLoader.GoldLoadMode loadMode = formal
                ? RequirementGraphGoldLoader.GoldLoadMode.FORMAL
                : RequirementGraphGoldLoader.GoldLoadMode.EXPLORATORY;
        List<GoldCase> cases = loader.load(dataset, loadMode);
        int limit = Integer.getInteger("gold.limit", 0);
        if (limit > 0 && limit < cases.size()) {
            cases = cases.subList(0, limit);
        }
        // 真实 BuildService 链路共享 SQLite store，只能串行构建，避免 SQLite 写锁竞争。
        int parallelism = build ? 1 : Integer.getInteger("gold.parallelism", 8);
        RequirementGraphGoldPredictor predictor;
        if (build) {
            // 完整生产构建链路：RequirementGraphWindowPlanner + ExtractionService + BuildAccumulator + Evidence + SQLite store
            predictor = buildPredictor();
        } else if (production) {
            // 生产抽取链路：走真实 RequirementGraphExtractionService（schema/证据/本体校验 + 跨窗口合并）
            predictor = new ProductionGraphPredictor(
                    new RequirementGraphExtractionService(chatClient, ragProperties, graphProperties));
        } else if (llm) {
            predictor = new PromptExtractionBenchmarkPredictor(chatClient, graphProperties);
        } else {
            predictor = new RuleGoldPredictor();
        }
        RequirementGraphGoldEvaluator evaluator = new RequirementGraphGoldEvaluator();
        // 完整 BuildService 链路单条可能执行多次模型调用 + SQLite 写入，放宽单条超时。
        long perCallTimeoutMs = build ? 900_000L : 120_000L;
        GoldEvalReport report = evaluator.evaluateParallel(cases, predictor, parallelism, perCallTimeoutMs);
        // 评测器自检：Oracle 应接近 1.0；Empty 应全 0（用于对比 LLM 到底比空预测好多少）
        GoldEvalReport oracleReport = evaluator.evaluate(cases, new OracleGoldPredictor());
        GoldEvalReport emptyReport = evaluator.evaluate(cases, new EmptyGoldPredictor());

        String predictorLabel = build ? "PRODUCTION_BUILD" : production ? "PRODUCTION" : llm ? "LLM" : "RULE";
        int acceptedCases = (int) cases.stream()
                .filter(caseItem -> "GOLD_ACCEPTED".equals(caseItem.annotationStatus())).count();
        String sourceContext = cases.stream().findFirst()
                .map(caseItem -> caseItem.projectId() + "/" + caseItem.documentId() + "/" + caseItem.requirementVersion())
                .orElse("-");
        String markdown = render(report, oracleReport, emptyReport, predictorLabel, formal, acceptedCases,
                sourceContext, cases.size());
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
        // —— 评测器质量门禁：Oracle 必须全 1.0，Empty 必须全 0 ——
        assertOracleGate(oracleReport, cases);
        assertEmptyGate(emptyReport);
    }

    private RequirementGraphGoldPredictor buildPredictor() throws Exception {
        Path temp = Files.createTempDirectory("gold-build-eval");
        Path db = temp.resolve("graph.db");
        RequirementGraphProperties localProperties = ProductionBuildGraphPredictor.withDatabasePath(graphProperties, db.toString());
        SQLiteRequirementGraphStore store = new SQLiteRequirementGraphStore(objectMapper, localProperties);
        ProductionBuildGraphPredictor.MapRequirementSnapshotRepository snapshots =
                new ProductionBuildGraphPredictor.MapRequirementSnapshotRepository(temp);
        com.example.requirementrag.config.ProjectRegistry registry =
                mock(com.example.requirementrag.config.ProjectRegistry.class);
        when(registry.resolveRequirementCollection(anyString())).thenReturn("requirements_gold");
        RequirementGraphBuildService buildService = new RequirementGraphBuildService(
                store,
                new RequirementGraphExtractionService(chatClient, ragProperties, graphProperties),
                snapshots,
                mock(com.example.requirementrag.retrieval.QdrantHybridStore.class),
                registry,
                localProperties);
        return new ProductionBuildGraphPredictor(buildService, store, snapshots);
    }

    private void assertOracleGate(GoldEvalReport oracleReport, List<GoldCase> cases) {
        ScenarioMetrics overall = oracleReport.overall();
        // 变量维度在切片中可能存在“无样本”情况（如 gold.limit 只取到无代码事实的用例），
        // 此时 0/0 返回 0.0，不能要求 1.0；完整数据集门禁由 DatasetSelfCheckTest 无条件覆盖。
        if (oracleReport.extractionCases() > 0) {
            assertThat(overall.entityF1()).as("Oracle 实体F1 自检").isEqualTo(1.0);
            assertThat(overall.relationF1()).as("Oracle 关系F1 自检").isEqualTo(1.0);
            assertThat(overall.claimF1()).as("Oracle ClaimF1 自检").isEqualTo(1.0);
        }
        if (cases.stream().anyMatch(caseItem -> caseItem.codeFacts() != null && !caseItem.codeFacts().isEmpty())) {
            assertThat(overall.codeFactRecall()).as("Oracle 代码事实召回 自检").isEqualTo(1.0);
            assertThat(overall.codeFactPrecision()).as("Oracle 代码事实精度 自检").isEqualTo(1.0);
        }
        if (cases.stream().anyMatch(caseItem ->
                caseItem.decision() != null && !caseItem.decision().type().isBlank())) {
            assertThat(overall.driftDecisionAccuracy()).as("Oracle 漂移决策准确率 自检").isEqualTo(1.0);
        }
        if (cases.stream().anyMatch(caseItem -> isNegativeScenario(caseItem.scenario()))) {
            assertThat(overall.negativeErrorRate()).as("Oracle 负例错误率 自检").isEqualTo(0.0);
        }
    }

    private boolean isNegativeScenario(String scenario) {
        return "DOUBT_NEGATIVE".equals(scenario)
                || "OPEN_DOUBT_NO_DRIFT".equals(scenario)
                || "DOCUMENT_CONFLICT".equals(scenario);
    }

    private void assertEmptyGate(GoldEvalReport emptyReport) {
        ScenarioMetrics overall = emptyReport.overall();
        assertThat(overall.entityF1()).as("Empty 实体F1 基线").isEqualTo(0.0);
        assertThat(overall.relationF1()).as("Empty 关系F1 基线").isEqualTo(0.0);
        assertThat(overall.claimF1()).as("Empty ClaimF1 基线").isEqualTo(0.0);
        assertThat(overall.codeFactRecall()).as("Empty 代码事实召回 基线").isEqualTo(0.0);
    }

    private String render(GoldEvalReport report, GoldEvalReport oracleReport, GoldEvalReport emptyReport,
                          String predictorLabel, boolean formal, int acceptedCases,
                          String sourceContext, int loadedCaseCount) {
        StringBuilder out = new StringBuilder();
        out.append("# 需求语义图金标评测报告\n\n");
        out.append("- dataset: requirement-graph-gold (predictor=").append(predictorLabel).append(")\n");
        out.append("- sourceContext: ").append(sourceContext).append("（来源 ").append(loadedCaseCount).append(" 条）\n");
        out.append("- evaluatedCases: ").append(report.totalCases()).append("\n");
        out.append("- acceptedCases: ").append(acceptedCases).append("\n");
        out.append("- formalEvaluation: ").append(formal).append("\n");
        out.append("- totalCases: ").append(report.totalCases()).append("\n");
        out.append("- extractionCases: ").append(report.extractionCases()).append("\n");
        out.append("- retrievalTestCases: ").append(report.retrievalCases())
                .append("（不计入抽取 F1）\n");
        out.append("- 匹配口径：一对一匹配 / Claim=factKey AND value / 代码事实=repo+commit+key+value\n");
        out.append("- goldEvidenceFieldCompletenessRate: ")
                .append(format(report.goldEvidenceFieldCompletenessRate())).append("\n");
        out.append("- goldEvidenceSourceMatchRate: ")
                .append(format((Double) report.extras().getOrDefault("goldEvidenceSourceMatchRate", Double.NaN)))
                .append("\n");
        out.append("- goldEvidenceOffsetValidityRate: ")
                .append(format((Double) report.extras().getOrDefault("goldEvidenceOffsetValidityRate", Double.NaN)))
                .append("\n");
        out.append("- goldEvidenceClaimSupportRate: ")
                .append(format((Double) report.extras().getOrDefault("goldEvidenceClaimSupportRate", Double.NaN)))
                .append("\n");
        out.append("- predictionStatusCounts: ")
                .append(report.extras().getOrDefault("predictionStatusCounts", Map.of())).append("\n");
        out.append("- predictionErrorCodeCounts: ")
                .append(report.extras().getOrDefault("predictionErrorCodeCounts", Map.of())).append("\n");
        out.append("- averageLatencyMs: ")
                .append(report.extras().getOrDefault("averageLatencyMs", 0)).append("\n");
        out.append("- predictionSuccessRate: ")
                .append(format((Double) report.extras().getOrDefault("predictionSuccessRate", Double.NaN)))
                .append("（failedCaseCount=").append(report.extras().getOrDefault("failedCaseCount", 0))
                .append("，partialFailureRate=")
                .append(format((Double) report.extras().getOrDefault("partialFailureRate", Double.NaN)))
                .append("，failedCaseEntityRecall=")
                .append(format((Double) report.extras().getOrDefault("failedCaseEntityRecall", Double.NaN)))
                .append("）\n");
        out.append("- 严格口径 strict：实体F1=")
                .append(format((Double) report.extras().getOrDefault("strictOverallEntityF1", Double.NaN)))
                .append(" 关系F1=").append(format((Double) report.extras().getOrDefault("strictOverallRelationF1", Double.NaN)))
                .append(" ClaimF1=").append(format((Double) report.extras().getOrDefault("strictOverallClaimF1", Double.NaN)))
                .append(" 代码事实F1=").append(format((Double) report.extras().getOrDefault("strictOverallCodeFactF1", Double.NaN)))
                .append("\n");
        out.append("- 仅成功样本 successfulOnly：实体F1=")
                .append(format((Double) report.extras().getOrDefault("successfulOnlyOverallEntityF1", Double.NaN)))
                .append(" 关系F1=").append(format((Double) report.extras().getOrDefault("successfulOnlyOverallRelationF1", Double.NaN)))
                .append(" ClaimF1=").append(format((Double) report.extras().getOrDefault("successfulOnlyOverallClaimF1", Double.NaN)))
                .append(" 代码事实F1=").append(format((Double) report.extras().getOrDefault("successfulOnlyOverallCodeFactF1", Double.NaN)))
                .append("\n");
        out.append("- 关系本体约束：ontologyAlignedRelationF1=")
                .append(format((Double) report.extras().getOrDefault("ontologyAlignedRelationF1", Double.NaN)))
                .append("（gold 本体关系 ").append(report.extras().getOrDefault("ontologyAlignedRelationCount", 0))
                .append(" 条 / 非本体 ").append(report.extras().getOrDefault("nonOntologyGoldRelationCount", 0))
                .append(" 条 / 边界约束 ").append(report.extras().getOrDefault("boundaryConstraintGoldRelationCount", 0))
                .append(" 条）\n\n");

        out.append("## 按场景\n\n");
        out.append("| 场景 | 用例 | 实体F1 | 关系F1 | ClaimF1 | 负例错误率 | 存疑召回 | 代码事实召回 | 代码事实F1 | 漂移准确率 |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (ScenarioMetrics metrics : report.scenarios()) {
            out.append('|').append(metrics.scenario())
                    .append('|').append(metrics.cases())
                    .append('|').append(format(metrics.entityF1()))
                    .append('|').append(format(metrics.relationF1()))
                    .append('|').append(format(metrics.claimF1()))
                    .append('|').append(format(metrics.negativeErrorRate()))
                    .append('|').append(format(metrics.uncertaintyRecall()))
                    .append('|').append(format(metrics.codeFactRecall()))
                    .append('|').append(format(metrics.codeFactF1()))
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
                .append('|').append(format(overall.codeFactF1()))
                .append('|').append(format(overall.driftDecisionAccuracy()))
                .append("|\n\n");

        out.append("## 评测器自检（Oracle / Empty）\n\n");
        out.append("| 预测器 | 实体F1 | 关系F1 | ClaimF1 | 负例错误率 | 存疑召回 | 代码事实召回 | 代码事实F1 | 漂移准确率 |\n");
        out.append("|---|---|---|---|---|---|---|---|---|\n");
        appendOverallRow(out, "Oracle", oracleReport);
        appendOverallRow(out, "Empty", emptyReport);
        out.append("\n> Oracle 必须接近 1.0、Empty 必须接近 0（本入口已作为 CI 门禁断言）；"
                + "若 Oracle 未达标，说明评测器/匹配契约有问题，不能继续调模型。\n");

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
                .append('|').append(format(overall.codeFactF1()))
                .append('|').append(format(overall.driftDecisionAccuracy()))
                .append("|\n");
    }

    private String format(double value) {
        if (Double.isNaN(value)) return "N/A";
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
