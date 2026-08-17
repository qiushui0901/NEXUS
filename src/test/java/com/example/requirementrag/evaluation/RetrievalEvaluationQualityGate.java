package com.example.requirementrag.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Applies versioned release thresholds to a completed real-dependency evaluation report. */
public final class RetrievalEvaluationQualityGate {

    private RetrievalEvaluationQualityGate() {
    }

    public static Thresholds loadResource(String resourcePath, ObjectMapper mapper) {
        InputStream input = RetrievalEvaluationQualityGate.class.getClassLoader().getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IllegalArgumentException("Evaluation threshold resource not found: " + resourcePath);
        }
        try (input) {
            return parse(mapper.readTree(input), resourcePath);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read evaluation threshold resource: " + resourcePath,
                    exception);
        }
    }

    static Thresholds parse(JsonNode root, String source) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Evaluation thresholds must be a JSON object: " + source);
        }
        if (root.has("minimum") || root.has("maximum")) {
            int schemaVersion = requiredInt(root, "schemaVersion", source);
            if (schemaVersion != 1) {
                throw new IllegalArgumentException("Unsupported evaluation threshold schemaVersion "
                        + schemaVersion + ": " + source);
            }
            String dataset = requiredText(root, "dataset", source);
            JsonNode minimum = requiredObject(root, "minimum", source);
            JsonNode maximum = requiredObject(root, "maximum", source);
            Thresholds thresholds = new Thresholds(
                    schemaVersion,
                    dataset,
                    optionalDouble(minimum, "documentRecallAt10"),
                    optionalDouble(minimum, "codeRecallAt10"),
                    optionalDouble(minimum, "mrrAt10"),
                    optionalDouble(minimum, "ndcgAt10"),
                    optionalDouble(minimum, "noResultAccuracy"),
                    optionalDouble(maximum, "degradationRate"),
                    optionalLong(maximum, "p95LatencyMs"));
            validate(thresholds, source);
            return thresholds;
        }

        Thresholds legacy = new Thresholds(
                0,
                null,
                optionalDouble(root, "documentRecallAt10"),
                optionalDouble(root, "codeRecallAt10"),
                optionalDouble(root, "mrrAt10"),
                null,
                null,
                null,
                optionalLong(root, "p95LatencyMs"));
        validate(legacy, source);
        return legacy;
    }

    public static GateResult evaluate(RetrievalEvaluationReport report, Thresholds thresholds) {
        List<String> failures = new ArrayList<>();
        if (thresholds.dataset() != null && !thresholds.dataset().equals(report.dataset())) {
            failures.add("数据集不匹配：阈值要求 " + thresholds.dataset() + "，实际为 " + report.dataset());
        }
        if (report.summary().infrastructureFailureCases() > 0) {
            failures.add("基础设施失败用例数必须为 0，实际为 "
                    + report.summary().infrastructureFailureCases());
        }

        RetrievalEvaluationReport.UniqueCaseSummary unique = report.uniqueCaseSummary();
        minimum(failures, "Document Recall@10", unique.documentRecallAt10(),
                unique.documentCases(), thresholds.documentRecallAt10());
        minimum(failures, "Code Recall@10", unique.codeRecallAt10(),
                unique.codeCases(), thresholds.codeRecallAt10());
        minimum(failures, "MRR@10", unique.mrrAt10(),
                unique.reciprocalRankItems(), thresholds.mrrAt10());
        minimum(failures, "nDCG@10", unique.ndcgAt10(),
                unique.ndcgItems(), thresholds.ndcgAt10());
        minimum(failures, "No-result accuracy", unique.noResultAccuracy(),
                unique.noResultCases(), thresholds.noResultAccuracy());
        maximum(failures, "Degradation rate", unique.degradationRate(),
                unique.totalCases(), thresholds.degradationRate());
        if (thresholds.p95LatencyMs() != null
                && report.summary().p95LatencyMs() > thresholds.p95LatencyMs()) {
            failures.add("P95 latency 超过上限：实际 " + report.summary().p95LatencyMs()
                    + " ms，上限 " + thresholds.p95LatencyMs() + " ms");
        }
        return new GateResult(failures.isEmpty(), List.copyOf(failures));
    }

    private static void minimum(
            List<String> failures, String metric, double actual, int items, Double threshold) {
        if (threshold == null) {
            return;
        }
        if (items == 0) {
            failures.add(metric + " 没有可评估样本");
        } else if (actual < threshold) {
            failures.add(metric + " 低于下限：实际 " + format(actual) + "，下限 " + format(threshold));
        }
    }

    private static void maximum(
            List<String> failures, String metric, double actual, int items, Double threshold) {
        if (threshold == null) {
            return;
        }
        if (items == 0) {
            failures.add(metric + " 没有可评估样本");
        } else if (actual > threshold) {
            failures.add(metric + " 超过上限：实际 " + format(actual) + "，上限 " + format(threshold));
        }
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    private static void validate(Thresholds thresholds, String source) {
        validateRate(thresholds.documentRecallAt10(), "documentRecallAt10", source);
        validateRate(thresholds.codeRecallAt10(), "codeRecallAt10", source);
        validateRate(thresholds.mrrAt10(), "mrrAt10", source);
        validateRate(thresholds.ndcgAt10(), "ndcgAt10", source);
        validateRate(thresholds.noResultAccuracy(), "noResultAccuracy", source);
        validateRate(thresholds.degradationRate(), "degradationRate", source);
        if (thresholds.p95LatencyMs() != null && thresholds.p95LatencyMs() < 0) {
            throw new IllegalArgumentException("p95LatencyMs must be non-negative: " + source);
        }
    }

    private static void validateRate(Double value, String name, String source) {
        if (value != null && (!Double.isFinite(value) || value < 0 || value > 1)) {
            throw new IllegalArgumentException(name + " must be between 0 and 1: " + source);
        }
    }

    private static JsonNode requiredObject(JsonNode root, String field, String source) {
        JsonNode value = root.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(field + " must be an object: " + source);
        }
        return value;
    }

    private static String requiredText(JsonNode root, String field, String source) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string: " + source);
        }
        return value.asText();
    }

    private static int requiredInt(JsonNode root, String field, String source) {
        JsonNode value = root.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer: " + source);
        }
        return value.intValue();
    }

    private static Double optionalDouble(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw new IllegalArgumentException(field + " must be numeric");
        }
        return value.doubleValue();
    }

    private static Long optionalLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.longValue();
    }

    public record Thresholds(
            int schemaVersion,
            String dataset,
            Double documentRecallAt10,
            Double codeRecallAt10,
            Double mrrAt10,
            Double ndcgAt10,
            Double noResultAccuracy,
            Double degradationRate,
            Long p95LatencyMs
    ) {
    }

    public record GateResult(boolean passed, List<String> failures) {
        public GateResult {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        public void requirePassed() {
            if (!passed) {
                throw new AssertionError("真实 RAG 评测质量门未通过：\n- " + String.join("\n- ", failures));
            }
        }
    }
}
