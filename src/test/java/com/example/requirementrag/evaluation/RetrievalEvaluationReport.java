package com.example.requirementrag.evaluation;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Reproducible JSON and Markdown report generated only under target/. */
public record RetrievalEvaluationReport(
        String dataset,
        String generatedAt,
        int cutoff,
        String mode,
        String classification,
        int warmupRuns,
        int repetitions,
        int datasetCaseCount,
        Summary summary,
        Map<String, Summary> profiles,
        Map<String, Integer> failureAttributions,
        List<RetrievalEvaluationMatcher.CaseResult> cases
) {
    public static RetrievalEvaluationReport create(
            String dataset, List<RetrievalEvaluationMatcher.CaseResult> cases) {
        return create(dataset, "calibration", 0, 1, cases);
    }

    public static RetrievalEvaluationReport create(
            String dataset,
            String mode,
            int warmups,
            int repetitions,
            List<RetrievalEvaluationMatcher.CaseResult> cases) {
        Map<String, Summary> perProfile = cases.stream().collect(Collectors.groupingBy(
                value -> value.profile().name(),
                TreeMap::new,
                Collectors.collectingAndThen(Collectors.toList(), RetrievalEvaluationReport::summarize)));
        int unique = (int) cases.stream().map(RetrievalEvaluationMatcher.CaseResult::id).distinct().count();
        String classification = unique >= 50 && repetitions > 0 ? "formal" : "calibration";
        return new RetrievalEvaluationReport(
                dataset,
                Instant.now().toString(),
                RetrievalEvaluationMatcher.DEFAULT_CUTOFF,
                mode,
                classification,
                warmups,
                repetitions,
                unique,
                summarize(cases),
                Map.copyOf(perProfile),
                summarizeFailureAttributions(cases),
                List.copyOf(cases));
    }

    public void write(Path directory, ObjectMapper mapper) throws IOException {
        Files.createDirectories(directory);
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("report.json").toFile(), this);
        Files.writeString(directory.resolve("report.md"), markdown(), StandardCharsets.UTF_8);
    }

    String markdown() {
        StringBuilder table = new StringBuilder("# Retrieval Evaluation Report\n\n");
        table.append("- Mode: `").append(mode).append("`\n")
                .append("- Classification: `").append(classification).append("`\n")
                .append("- Dataset: `").append(dataset).append("`\n")
                .append("- Dataset cases: ").append(datasetCaseCount).append('\n')
                .append("- Warm-up runs: ").append(warmupRuns).append('\n')
                .append("- Repetitions: ").append(repetitions).append("\n\n");
        appendSummary(table, "Overall", summary);
        profiles.forEach((name, value) -> appendSummary(table, name, value));
        appendFailureAttributions(table);
        appendCases(table);
        return table.toString();
    }

    private void appendFailureAttributions(StringBuilder table) {
        table.append("## Failure attribution\n\n")
                .append("| Stage cause | Count |\n")
                .append("|---|---:|\n");
        if (failureAttributions.isEmpty()) {
            table.append("|None|0|\n");
        } else {
            failureAttributions.forEach((cause, count) -> table.append('|')
                    .append(escape(cause)).append('|').append(count).append("|\n"));
        }
        table.append('\n');
    }

    private void appendCases(StringBuilder table) {
        table.append("## Cases\n\n")
                .append("| ID | Rep | Profile | Success | Doc raw→BGE-in→BGE-out→final | Doc move | ")
                .append("Code raw→ranked→final | Code move | Latency ms | Attribution | Warnings |\n")
                .append("|---|---:|---|---:|---|---|---|---|---:|---|---|\n");
        for (RetrievalEvaluationMatcher.CaseResult result : cases) {
            table.append('|').append(escape(result.id()))
                    .append('|').append(result.repetition())
                    .append('|').append(result.profile())
                    .append('|').append(result.success() ? "PASS" : "FAIL")
                    .append('|').append(rankPath(result.documentRawRank(), result.documentRerankInputRank(),
                            result.documentRerankedRank(), result.documentRank()))
                    .append('|').append(result.documentRankMovement())
                    .append('|').append(rankPath(result.codeRawRank(), result.codeRankedRank(), result.codeRank()))
                    .append('|').append(result.codeRankMovement())
                    .append('|').append(result.totalLatencyMs())
                    .append('|').append(escape(String.join(",", result.failureAttributions())))
                    .append('|').append(escape(result.warnings().stream().map(warning -> warning.code())
                            .distinct().collect(Collectors.joining(","))))
                    .append("|\n");
        }
    }

    private static void appendSummary(StringBuilder table, String title, Summary summary) {
        table.append("## ").append(title).append("\n\n")
                .append("| Metric | Value | Raw |\n")
                .append("|---|---:|---:|\n");
        metric(table, "Document Recall@10", summary.documentRecallAt10(),
                summary.documentHits(), summary.documentCases());
        metric(table, "Code Recall@10", summary.codeRecallAt10(),
                summary.codeHits(), summary.codeCases());
        table.append("|MRR@10|")
                .append(String.format(Locale.ROOT, "%.3f", summary.mrrAt10()))
                .append('|').append(String.format(Locale.ROOT, "%.3f", summary.reciprocalRankSum()))
                .append('/').append(summary.reciprocalRankItems()).append("|\n")
                .append("\nP50 ").append(summary.p50LatencyMs()).append(" ms; P95 ")
                .append(summary.p95LatencyMs())
                .append(" ms; BGE attempts/success/degradation/no-candidate/singleton skips ")
                .append(summary.bgeCalls()).append('/').append(summary.bgeSuccesses()).append('/')
                .append(summary.bgeDegradations()).append('/').append(summary.bgeNoCandidateSkips())
                .append('/').append(summary.bgeSingletonSkips())
                .append(".\n\n");
    }

    private static Summary summarize(List<RetrievalEvaluationMatcher.CaseResult> cases) {
        int documentCases = 0;
        int documentHits = 0;
        int codeCases = 0;
        int codeHits = 0;
        int mixedCases = 0;
        int mixedHits = 0;
        int noResultCases = 0;
        int noResultHits = 0;
        int failed = 0;
        int infrastructureFailures = 0;
        int reciprocalRankItems = 0;
        int bgeCalls = 0;
        int bgeSuccesses = 0;
        int bgeDegradations = 0;
        int bgeSkips = 0;
        int bgeSingletonSkips = 0;
        double reciprocalRankSum = 0;

        for (RetrievalEvaluationMatcher.CaseResult result : cases) {
            if (result.expectsDocuments()) {
                documentCases++;
                reciprocalRankItems++;
                if (result.documentRank() != null) {
                    documentHits++;
                    reciprocalRankSum += 1d / result.documentRank();
                }
            }
            if (result.expectsCode()) {
                codeCases++;
                reciprocalRankItems++;
                if (result.codeRank() != null) {
                    codeHits++;
                    reciprocalRankSum += 1d / result.codeRank();
                }
            }
            if (result.expectsDocuments() && result.expectsCode()) {
                mixedCases++;
                if (result.documentRank() != null && result.codeRank() != null) {
                    mixedHits++;
                }
            }
            if (result.expectedOutcome() == RetrievalEvaluationCase.ExpectedOutcome.NO_RESULTS) {
                noResultCases++;
                if (result.success()) {
                    noResultHits++;
                }
            }
            if (!result.success()) {
                failed++;
            }
            if (isInfrastructureFailure(result)) {
                infrastructureFailures++;
            }
            bgeCalls += result.bgeCalls();
            bgeSuccesses += result.bgeSuccesses();
            bgeDegradations += result.bgeDegradations();
            bgeSkips += result.bgeNoCandidateSkips();
            bgeSingletonSkips += result.bgeSingletonSkips();
        }

        List<Long> latencies = cases.stream()
                .map(RetrievalEvaluationMatcher.CaseResult::totalLatencyMs)
                .sorted()
                .toList();
        return new Summary(
                cases.size(), failed, infrastructureFailures,
                documentCases, documentHits, rate(documentHits, documentCases),
                codeCases, codeHits, rate(codeHits, codeCases),
                reciprocalRankItems, reciprocalRankSum, rate(reciprocalRankSum, reciprocalRankItems),
                mixedCases, mixedHits, rate(mixedHits, mixedCases),
                noResultCases, noResultHits, rate(noResultHits, noResultCases),
                percentile(latencies, .5), percentile(latencies, .95),
                bgeCalls, bgeSuccesses, bgeDegradations, bgeSkips, bgeSingletonSkips);
    }

    private static Map<String, Integer> summarizeFailureAttributions(
            List<RetrievalEvaluationMatcher.CaseResult> cases) {
        Map<String, Integer> counts = new TreeMap<>();
        cases.stream().flatMap(result -> result.failureAttributions().stream())
                .forEach(cause -> counts.merge(cause, 1, Integer::sum));
        return Map.copyOf(new LinkedHashMap<>(counts));
    }

    private static boolean isInfrastructureFailure(RetrievalEvaluationMatcher.CaseResult result) {
        return result.documentError() != null || result.codeError() != null
                || result.warnings().stream().anyMatch(warning -> warning.code() != null
                && warning.code().endsWith("_UNAVAILABLE"));
    }

    private static double rate(double numerator, int denominator) {
        return denominator == 0 ? 0 : numerator / denominator;
    }

    private static long percentile(List<Long> sorted, double quantile) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.max(0, (int) Math.ceil(quantile * sorted.size()) - 1));
    }

    private static void metric(StringBuilder table, String name, double value, int hits, int cases) {
        table.append('|').append(name).append('|')
                .append(String.format(Locale.ROOT, "%.3f", value)).append('|')
                .append(hits).append('/').append(cases).append("|\n");
    }

    private static String rankPath(Integer... ranks) {
        return java.util.Arrays.stream(ranks).map(RetrievalEvaluationReport::rank)
                .collect(Collectors.joining("→"));
    }

    private static String rank(Integer rank) {
        return rank == null ? "-" : rank.toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|");
    }

    public record Summary(
            int totalCases,
            int failedCases,
            int infrastructureFailureCases,
            int documentCases,
            int documentHits,
            double documentRecallAt10,
            int codeCases,
            int codeHits,
            double codeRecallAt10,
            int reciprocalRankItems,
            double reciprocalRankSum,
            double mrrAt10,
            int mixedCases,
            int mixedBothHits,
            double mixedBothHitRate,
            int noResultCases,
            int noResultHits,
            double noResultAccuracy,
            long p50LatencyMs,
            long p95LatencyMs,
            int bgeCalls,
            int bgeSuccesses,
            int bgeDegradations,
            int bgeNoCandidateSkips,
            int bgeSingletonSkips
    ) {
    }
}
