package com.example.requirementrag.evolution.evaluation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 检索评测指标计算工具，与现有评测口径保持一致。 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /** 单个样本结果。 */
    public static ExperimentReport.CaseResult caseResult(String caseId, String query,
                                                         List<String> predictedIds,
                                                         List<String> relevantIds,
                                                         long latencyMs, String status) {
        int firstRelevantRank = firstRelevantRank(predictedIds, relevantIds);
        return new ExperimentReport.CaseResult(caseId, query, predictedIds, relevantIds,
                firstRelevantRank,
                firstRelevantRank > 0 && firstRelevantRank <= 1,
                firstRelevantRank > 0 && firstRelevantRank <= 5,
                firstRelevantRank > 0 && firstRelevantRank <= 10,
                latencyMs, status);
    }

    public static double recallAt(List<String> predictedIds, List<String> relevantIds, int k) {
        if (relevantIds.isEmpty()) {
            return 0.0;
        }
        Set<String> relevant = new HashSet<>(relevantIds);
        long hits = predictedIds.stream().limit(k).filter(relevant::contains).count();
        return (double) hits / relevant.size();
    }

    public static double mrrAt10(List<String> predictedIds, List<String> relevantIds) {
        int rank = firstRelevantRank(predictedIds, relevantIds);
        if (rank <= 0 || rank > 10) {
            return 0.0;
        }
        return 1.0 / rank;
    }

    public static double ndcgAt10(List<String> predictedIds, List<String> relevantIds) {
        Set<String> relevant = new HashSet<>(relevantIds);
        double dcg = 0.0;
        int hits = 0;
        for (int i = 0; i < Math.min(predictedIds.size(), 10); i++) {
            if (relevant.contains(predictedIds.get(i))) {
                hits++;
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        if (hits == 0) {
            return 0.0;
        }
        double idcg = 0.0;
        for (int i = 0; i < Math.min(relevant.size(), 10); i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    public static double percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(index);
    }

    private static int firstRelevantRank(List<String> predictedIds, List<String> relevantIds) {
        Set<String> relevant = new HashSet<>(relevantIds);
        for (int i = 0; i < predictedIds.size(); i++) {
            if (relevant.contains(predictedIds.get(i))) {
                return i + 1;
            }
        }
        return -1;
    }
}
