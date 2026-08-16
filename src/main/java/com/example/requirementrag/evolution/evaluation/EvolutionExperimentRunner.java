package com.example.requirementrag.evolution.evaluation;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.evolution.policy.RetrievalPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 策略实验运行器：在同一数据集上运行基线和候选策略，输出可比较报告。 */
@Service
public class EvolutionExperimentRunner {

    private static final Logger log = LoggerFactory.getLogger(EvolutionExperimentRunner.class);

    private final RetrievalPolicyExecutor policyExecutor;
    private final ObjectMapper objectMapper;
    private final Path reportRoot;

    public EvolutionExperimentRunner(RetrievalPolicyExecutor policyExecutor, ObjectMapper objectMapper,
                                     RagProperties properties) {
        this.policyExecutor = policyExecutor;
        this.objectMapper = objectMapper;
        this.reportRoot = Path.of(properties.evolution().datasetRootPath()).toAbsolutePath().normalize()
                .getParent().resolve("experiments");
    }

    public ExperimentReport run(EvaluationDataset dataset, RetrievalPolicy baseline,
                                RetrievalPolicy candidate, String indexVersion, String modelVersion,
                                long randomSeed, int repetitions) {
        String experimentId = "exp-" + UUID.randomUUID().toString().substring(0, 8);
        ExperimentManifest manifest = new ExperimentManifest(experimentId,
                baseline == null ? null : baseline.policyId(),
                baseline == null ? null : baseline.version(),
                candidate == null ? null : candidate.policyId(),
                candidate == null ? null : candidate.version(),
                dataset.version(), indexVersion, modelVersion, randomSeed, repetitions, java.time.Instant.now());
        int effectiveRepetitions = Math.max(1, repetitions);
        List<ExperimentReport.CaseResult> baselineCases = new ArrayList<>();
        List<ExperimentReport.CaseResult> candidateCases = new ArrayList<>();
        for (EvaluationCase evalCase : dataset.cases()) {
            for (int repetition = 1; repetition <= effectiveRepetitions; repetition++) {
                baselineCases.add(runOnce(evalCase, baseline, randomSeed, repetition, "baseline"));
                candidateCases.add(runOnce(evalCase, candidate, randomSeed, repetition, "candidate"));
            }
        }
        ExperimentReport.MetricSummary baselineSummary = summarize(baselineCases);
        ExperimentReport.MetricSummary candidateSummary = summarize(candidateCases);
        boolean passedGate = candidateSummary.recallAt1() >= baselineSummary.recallAt1()
                && candidateSummary.recallAt10() >= baselineSummary.recallAt10()
                && candidateSummary.ndcgAt10() >= baselineSummary.ndcgAt10() - 0.005
                && candidateSummary.p95Ms() <= baselineSummary.p95Ms() * 1.10;
        ExperimentReport report = new ExperimentReport(manifest, candidateCases, baselineSummary,
                candidateSummary, passedGate);
        save(report);
        return report;
    }

    private ExperimentReport.CaseResult runOnce(EvaluationCase evalCase, RetrievalPolicy policy,
                                                 long randomSeed, int repetition, String label) {
        long start = System.nanoTime();
        String status = "SUCCESS";
        List<String> ids;
        try {
            RetrievalPolicyExecutor.ExecutionResult result =
                    policyExecutor.execute(evalCase, policy, randomSeed, repetition);
            ids = result.ids();
            status = result.status();
        } catch (RuntimeException exception) {
            log.warn("Experiment {} failed for case {} ({}): {}", label, evalCase.caseId(),
                    repetition, exception.toString());
            ids = List.of();
            status = "FAILED";
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        String caseId = evalCase.caseId() + "#" + repetition;
        return RetrievalMetrics.caseResult(caseId, evalCase.query(), ids, evalCase.relevantIds(),
                elapsedMs, status);
    }

    private ExperimentReport.MetricSummary summarize(List<ExperimentReport.CaseResult> cases) {
        int n = cases.size();
        double recall1 = 0;
        double recall5 = 0;
        double recall10 = 0;
        double mrr = 0;
        double ndcg = 0;
        List<Long> latencies = new ArrayList<>();
        int failed = 0;
        int degraded = 0;
        for (ExperimentReport.CaseResult result : cases) {
            recall1 += result.recallAt1() ? 1 : 0;
            recall5 += result.recallAt5() ? 1 : 0;
            recall10 += result.recallAt10() ? 1 : 0;
            mrr += RetrievalMetrics.mrrAt10(result.predictedIds(), result.relevantIds());
            ndcg += RetrievalMetrics.ndcgAt10(result.predictedIds(), result.relevantIds());
            latencies.add(result.latencyMs());
            if ("FAILED".equals(result.status())) failed++;
            if ("DEGRADED".equals(result.status())) degraded++;
        }
        double divisor = n == 0 ? 1 : n;
        return new ExperimentReport.MetricSummary(
                recall1 / divisor,
                recall5 / divisor,
                recall10 / divisor,
                mrr / divisor,
                ndcg / divisor,
                RetrievalMetrics.percentile(latencies, 50),
                RetrievalMetrics.percentile(latencies, 95),
                RetrievalMetrics.percentile(latencies, 99),
                failed / divisor,
                degraded / divisor,
                0.0
        );
    }

    /** 按实验 ID 读取已保存的报告；不存在时返回 null。 */
    public ExperimentReport find(String experimentId) {
        if (experimentId == null || experimentId.isBlank()) {
            return null;
        }
        Path file = reportRoot.resolve(safeId(experimentId) + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(Files.readAllBytes(file), ExperimentReport.class);
        } catch (IOException exception) {
            log.warn("Unable to read experiment report {}", experimentId, exception);
            return null;
        }
    }

    private void save(ExperimentReport report) {
        try {
            Files.createDirectories(reportRoot);
            Path file = reportRoot.resolve(safeId(report.manifest().experimentId()) + ".json");
            Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(report) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            log.warn("Unable to save experiment report", exception);
        }
    }

    private String safeId(String value) {
        String normalized = value == null || value.isBlank() ? "unknown" : value.trim();
        if (!normalized.matches("[A-Za-z0-9._-]{1,128}") || normalized.contains("..")) {
            throw new IllegalArgumentException("experiment id contains unsafe characters");
        }
        return normalized;
    }
}
