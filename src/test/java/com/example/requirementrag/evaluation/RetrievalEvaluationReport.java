package com.example.requirementrag.evaluation;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** JSON and Markdown baseline report generated only under target/. */
public record RetrievalEvaluationReport(
        String dataset,
        String generatedAt,
        int cutoff,
        Summary summary,
        List<RetrievalEvaluationMatcher.CaseResult> cases
) {
    public static RetrievalEvaluationReport create(String dataset,
                                                   List<RetrievalEvaluationMatcher.CaseResult> cases) {
        return new RetrievalEvaluationReport(dataset, Instant.now().toString(),
                RetrievalEvaluationMatcher.DEFAULT_CUTOFF, summarize(cases), List.copyOf(cases));
    }

    public void write(Path outputDirectory, ObjectMapper objectMapper) throws IOException {
        Files.createDirectories(outputDirectory);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputDirectory.resolve("report.json").toFile(), this);
        Files.writeString(outputDirectory.resolve("report.md"), markdown(), StandardCharsets.UTF_8);
    }

    String markdown() {
        StringBuilder text = new StringBuilder();
        text.append("# Retrieval Evaluation Baseline\n\n");
        text.append("- Dataset: `").append(dataset).append("`\n");
        text.append("- Generated at: ").append(generatedAt).append("\n");
        text.append("- Cutoff: ").append(cutoff).append("\n");
        text.append("- Cases: ").append(summary.totalCases()).append("\n");
        text.append("- Failed cases: ").append(summary.failedCases()).append("\n");
        text.append("- Infrastructure failure cases: ").append(summary.infrastructureFailureCases()).append("\n\n");
        text.append("## Metrics\n\n");
        text.append("| Metric | Value | Raw |\n|---|---:|---:|\n");
        metric(text, "Document Recall@10", summary.documentRecallAt10(), summary.documentHits(), summary.documentCases());
        metric(text, "Code Recall@10", summary.codeRecallAt10(), summary.codeHits(), summary.codeCases());
        metric(text, "MRR@10", summary.mrrAt10(), summary.reciprocalRankItems(), summary.reciprocalRankItems());
        metric(text, "Mixed both-hit", summary.mixedBothHitRate(), summary.mixedBothHits(), summary.mixedCases());
        metric(text, "No-result accuracy", summary.noResultAccuracy(), summary.noResultHits(), summary.noResultCases());
        text.append("\nLatency: P50 ").append(summary.p50LatencyMs()).append(" ms, P95 ")
                .append(summary.p95LatencyMs()).append(" ms.\n\n");
        text.append("## Cases\n\n");
        text.append("| ID | Outcome | Success | Doc rank | Code rank | Latency ms | Candidates (doc/code) | Errors |\n");
        text.append("|---|---|---:|---:|---:|---:|---:|---|\n");
        for (RetrievalEvaluationMatcher.CaseResult result : cases) {
            text.append('|').append(escape(result.id()))
                    .append('|').append(result.expectedOutcome())
                    .append('|').append(result.success() ? "PASS" : "FAIL")
                    .append('|').append(rank(result.documentRank()))
                    .append('|').append(rank(result.codeRank()))
                    .append('|').append(result.totalLatencyMs())
                    .append('|').append(result.documentCandidateCount()).append('/').append(result.codeCandidateCount())
                    .append('|').append(escape(errors(result)))
                    .append("|\n");
        }
        return text.toString();
    }

    private static Summary summarize(List<RetrievalEvaluationMatcher.CaseResult> cases) {
        int documentCases = 0;
        int documentHits = 0;
        int codeCases = 0;
        int codeHits = 0;
        int mixedCases = 0;
        int mixedBothHits = 0;
        int noResultCases = 0;
        int noResultHits = 0;
        int failedCases = 0;
        int infrastructureFailureCases = 0;
        double reciprocalRankSum = 0.0;
        int reciprocalRankItems = 0;

        for (RetrievalEvaluationMatcher.CaseResult result : cases) {
            if (result.expectsDocuments()) {
                documentCases++;
                reciprocalRankItems++;
                if (result.documentRank() != null) {
                    documentHits++;
                    reciprocalRankSum += 1.0 / result.documentRank();
                }
            }
            if (result.expectsCode()) {
                codeCases++;
                reciprocalRankItems++;
                if (result.codeRank() != null) {
                    codeHits++;
                    reciprocalRankSum += 1.0 / result.codeRank();
                }
            }
            if (result.expectsDocuments() && result.expectsCode()) {
                mixedCases++;
                if (result.documentRank() != null && result.codeRank() != null) {
                    mixedBothHits++;
                }
            }
            if (result.expectedOutcome() == RetrievalEvaluationCase.ExpectedOutcome.NO_RESULTS) {
                noResultCases++;
                if (result.success()) {
                    noResultHits++;
                }
            }
            if (!result.success()) {
                failedCases++;
            }
            if (result.documentError() != null || result.codeError() != null) {
                infrastructureFailureCases++;
            }
        }

        List<Long> latencies = cases.stream().map(RetrievalEvaluationMatcher.CaseResult::totalLatencyMs)
                .sorted(Comparator.naturalOrder()).toList();
        return new Summary(cases.size(), failedCases, infrastructureFailureCases,
                documentCases, documentHits, rate(documentHits, documentCases),
                codeCases, codeHits, rate(codeHits, codeCases),
                reciprocalRankItems, rate(reciprocalRankSum, reciprocalRankItems),
                mixedCases, mixedBothHits, rate(mixedBothHits, mixedCases),
                noResultCases, noResultHits, rate(noResultHits, noResultCases),
                percentile(latencies, 0.50), percentile(latencies, 0.95));
    }

    private static double rate(double numerator, int denominator) {
        return denominator == 0 ? 0.0 : numerator / denominator;
    }

    private static long percentile(List<Long> sorted, double quantile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(quantile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static void metric(StringBuilder text, String name, double value, int numerator, int denominator) {
        text.append('|').append(name).append('|')
                .append(String.format(Locale.ROOT, "%.3f", value))
                .append('|').append(numerator).append('/').append(denominator).append("|\n");
    }

    private static String errors(RetrievalEvaluationMatcher.CaseResult result) {
        String document = result.documentError() == null ? "" : "document: " + result.documentError();
        String code = result.codeError() == null ? "" : "code: " + result.codeError();
        return document.isEmpty() ? code : code.isEmpty() ? document : document + "; " + code;
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
            double mrrAt10,
            int mixedCases,
            int mixedBothHits,
            double mixedBothHitRate,
            int noResultCases,
            int noResultHits,
            double noResultAccuracy,
            long p50LatencyMs,
            long p95LatencyMs
    ) {
    }
}
