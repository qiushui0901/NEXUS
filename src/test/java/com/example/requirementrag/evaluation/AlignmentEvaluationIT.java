package com.example.requirementrag.evaluation;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.knowledge.multisource.alignment.BusinessConceptService;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricAlignmentStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.AlignmentRelation;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DoubtImpact;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DriftItem;
import com.example.requirementrag.knowledge.multisource.alignment.CodeParameterAlignmentService;
import com.example.requirementrag.knowledge.multisource.alignment.CodeTestAlignmentService;
import com.example.requirementrag.knowledge.multisource.alignment.DoubtImpactService;
import com.example.requirementrag.knowledge.multisource.alignment.RequirementCodeDriftService;
import com.example.requirementrag.knowledge.multisource.alignment.VersionContextService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 代码事实基线对齐真实数据评测入口。
 *
 * <p>只在显式开启系统属性时才运行：{@code -Dalignment.eval=true}。
 * 使用本地真实库（multi-source + code-graph）构建 alignment 结果，并根据
 * {@code src/test/resources/alignment-eval/*.golden.jsonl} 计算 Precision/Recall/F1。
 * 金标文件为空时退化为“覆盖/诊断报告”，不执行指标断言。
 */
@SpringBootTest(properties = {
        "logging.structured.format.console=",
        "management.tracing.sampling.probability=0",
        "app.rag.knowledge.bootstrap-enabled=false",
        "app.rag.auth.enabled=false"
})
@EnabledIfSystemProperty(named = "alignment.eval", matches = "true")
class AlignmentEvaluationIT {

    private static final String PROJECT_ID = "immortal";
    private static final String VERSION = "5.1";
    private static final String ENVIRONMENT = "eval";

    @Autowired private MultiSourceKnowledgeStore knowledgeStore;
    @Autowired private CodeCentricAlignmentStore alignmentStore;
    @Autowired private VersionContextService versionContextService;
    @Autowired private BusinessConceptService businessConceptService;
    @Autowired private CodeParameterAlignmentService codeParameterAlignmentService;
    @Autowired private CodeTestAlignmentService codeTestAlignmentService;
    @Autowired private RequirementCodeDriftService requirementCodeDriftService;
    @Autowired private DoubtImpactService doubtImpactService;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void evaluatesAlignmentOnRealData() throws Exception {
        String contextId = versionContextService.resolve(PROJECT_ID, VERSION, ENVIRONMENT).contextId();

        businessConceptService.build(PROJECT_ID, VERSION);
        codeParameterAlignmentService.build(PROJECT_ID, VERSION, ENVIRONMENT);
        codeTestAlignmentService.build(PROJECT_ID, VERSION, ENVIRONMENT);
        requirementCodeDriftService.build(PROJECT_ID, VERSION, ENVIRONMENT);
        doubtImpactService.build(PROJECT_ID, VERSION, ENVIRONMENT);

        List<Golden> goldens = new ArrayList<>();
        goldens.addAll(loadGoldens("code-param.golden.jsonl"));
        goldens.addAll(loadGoldens("code-test.golden.jsonl"));
        goldens.addAll(loadGoldens("drift.golden.jsonl"));

        Map<String, Set<String>> paramPredictions = relationTargetsBySource(contextId, "READS_CONFIG");
        Map<String, Set<String>> testPredictions = relationTargetsBySource(contextId, "VERIFIES");
        Map<String, String> driftPredictions = driftTypeBySource(contextId);

        long relationCount = alignmentStore.findAlignmentRelations(PROJECT_ID, VERSION, contextId, null).size();
        long driftCount = alignmentStore.findDriftItems(PROJECT_ID, VERSION, contextId, null).size();
        long doubtImpactCount = alignmentStore.findDoubtImpacts(PROJECT_ID, VERSION, contextId, null).size();

        StringBuilder report = new StringBuilder();
        report.append("# 代码事实基线对齐评测报告\n\n");
        report.append("- projectId: ").append(PROJECT_ID).append("\n");
        report.append("- version: ").append(VERSION).append("\n");
        report.append("- environment: ").append(ENVIRONMENT).append("\n");
        report.append("- contextId: ").append(contextId).append("\n");
        report.append("- commitSha: ")
                .append(versionContextService.find(PROJECT_ID, VERSION, ENVIRONMENT)
                        .map(vc -> vc.commitSha() == null ? "N/A" : vc.commitSha()).orElse("N/A")).append("\n\n");

        report.append("## 覆盖/诊断统计\n\n");
        report.append("| 指标 | 数量 |\n|---|---|\n");
        report.append("| 参数 Claim | ").append(knowledgeStore.findParameters(PROJECT_ID, VERSION).size()).append(" |\n");
        report.append("| 测试用例 Claim | ").append(knowledgeStore.findTestCases(PROJECT_ID, VERSION).size()).append(" |\n");
        report.append("| 需求 Claim | ").append(knowledgeStore.findClaimsByProjectVersion(PROJECT_ID, VERSION).stream()
                .filter(c -> c.sourceType() == com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType.REQUIREMENT)
                .count()).append(" |\n");
        report.append("| AlignmentRelation | ").append(relationCount).append(" |\n");
        report.append("| DriftItem | ").append(driftCount).append(" |\n");
        report.append("| DoubtImpact | ").append(doubtImpactCount).append(" |\n\n");

        Metrics paramMetrics = evaluateLinkGoldens(goldens, "code-param", paramPredictions);
        Metrics testMetrics = evaluateLinkGoldens(goldens, "code-test", testPredictions);
        DriftMetrics driftMetrics = evaluateDriftGoldens(goldens, driftPredictions);

        report.append("## 金标评测\n\n");
        report.append("### 代码—参数（READS_CONFIG）\n\n");
        report.append(paramMetrics.print()).append("\n");
        report.append("### 代码—测试（VERIFIES）\n\n");
        report.append(testMetrics.print()).append("\n");
        report.append("### 需求—代码漂移\n\n");
        report.append(driftMetrics.print()).append("\n");

        Path reportPath = Path.of("target/alignment-eval-report.md").toAbsolutePath().normalize();
        Files.writeString(reportPath, report.toString(), StandardCharsets.UTF_8);

        System.out.println("[AlignmentEval] report written to " + reportPath);

        long goldenCount = goldens.size();
        if (goldenCount > 0) {
            // 有金标时至少保证能写出真实指标；不设固定阈值，避免把“真实基线”当门禁写死。
            assertThat(paramMetrics.total() + testMetrics.total() + driftMetrics.total())
                    .isEqualTo(goldenCount);
        } else {
            System.out.println("[AlignmentEval] no golden lines yet; coverage report only");
        }
    }

    private Map<String, Set<String>> relationTargetsBySource(String contextId, String relationType) {
        Map<String, Set<String>> result = new HashMap<>();
        for (AlignmentRelation relation : alignmentStore.findAlignmentRelations(
                PROJECT_ID, VERSION, contextId, relationType)) {
            result.computeIfAbsent(relation.sourceClaimId(), ignored -> new HashSet<>())
                    .add(relation.targetExternalId() == null ? relation.targetClaimId() : relation.targetExternalId());
        }
        return result;
    }

    private Map<String, String> driftTypeBySource(String contextId) {
        Map<String, String> result = new LinkedHashMap<>();
        for (DriftItem item : alignmentStore.findDriftItems(PROJECT_ID, VERSION, contextId, null)) {
            result.putIfAbsent(item.sourceClaimId(), item.driftType());
        }
        return result;
    }

    private List<Golden> loadGoldens(String resource) throws Exception {
        List<Golden> goldens = new ArrayList<>();
        ClassPathResource file = new ClassPathResource("alignment-eval/" + resource);
        if (!file.exists()) {
            return goldens;
        }
        try (InputStream in = file.getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line == null || line.isBlank()) continue;
                JsonNode node = objectMapper.readTree(line);
                goldens.add(new Golden(
                        node.path("kind").asText(),
                        node.path("sourceId").asText(),
                        node.path("expectedTargetId").asText(null),
                        node.path("expectedLabel").asText(null)));
            }
        }
        return goldens;
    }

    private Metrics evaluateLinkGoldens(List<Golden> goldens, String kind, Map<String, Set<String>> predicted) {
        int total = 0;
        int tp = 0;
        int precisionDenom = 0;
        int recallDenom = 0;
        for (Golden golden : goldens) {
            if (!kind.equals(golden.kind())) continue;
            total++;
            Set<String> expected = new LinkedHashSet<>();
            if (golden.expectedTargetId() != null && !golden.expectedTargetId().isBlank()) {
                expected.add(golden.expectedTargetId());
            }
            Set<String> actual = predicted.getOrDefault(golden.sourceId(), Set.of());
            int correct = 0;
            for (String id : actual) {
                if (expected.contains(id)) correct++;
            }
            tp += correct;
            precisionDenom += actual.size();
            if (!expected.isEmpty()) {
                recallDenom++;
            }
        }
        double precision = precisionDenom == 0 ? 0 : tp / (double) precisionDenom;
        double recall = recallDenom == 0 ? 0 : tp / (double) recallDenom;
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        return new Metrics(total, tp, precisionDenom, recallDenom, precision, recall, f1);
    }

    private DriftMetrics evaluateDriftGoldens(List<Golden> goldens, Map<String, String> predicted) {
        int total = 0;
        int correct = 0;
        Map<String, int[]> confusion = new LinkedHashMap<>();
        for (Golden golden : goldens) {
            if (!"drift".equals(golden.kind())) continue;
            total++;
            String expected = golden.expectedLabel() == null ? "" : golden.expectedLabel();
            String actual = predicted.getOrDefault(golden.sourceId(), "NO_PREDICTION");
            if (expected.equals(actual)) correct++;
            confusion.computeIfAbsent(expected, ignored -> new int[2])[0]++;
            if (expected.equals(actual)) confusion.get(expected)[1]++;
        }
        return new DriftMetrics(total, correct, confusion);
    }

    private record Golden(String kind, String sourceId, String expectedTargetId, String expectedLabel) {
    }

    private record Metrics(int total, int tp, int precisionDenom, int recallDenom,
                           double precision, double recall, double f1) {
        String print() {
            return "- 金标样本: " + total + "\n"
                    + "- TP: " + tp + "\n"
                    + "- Precision: " + format(precision) + " (" + tp + "/" + precisionDenom + ")\n"
                    + "- Recall: " + format(recall) + " (" + tp + "/" + recallDenom + ")\n"
                    + "- F1: " + format(f1) + "\n";
        }
    }

    private record DriftMetrics(int total, int correct, Map<String, int[]> confusion) {
        String print() {
            StringBuilder out = new StringBuilder();
            out.append("- 金标样本: ").append(total).append("\n");
            out.append("- Accuracy: ").append(total == 0 ? "0.0000" : format(correct / (double) total))
                    .append(" (").append(correct).append("/").append(total).append(")\n");
            out.append("- 按预期标签 TP/Total:\n");
            for (Map.Entry<String, int[]> entry : confusion.entrySet()) {
                out.append("  - ").append(entry.getKey().isBlank() ? "(无预期)" : entry.getKey())
                        .append(": ").append(entry.getValue()[1]).append("/").append(entry.getValue()[0]).append("\n");
            }
            return out.toString();
        }
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }
}