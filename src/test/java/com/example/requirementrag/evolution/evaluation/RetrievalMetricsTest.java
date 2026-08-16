package com.example.requirementrag.evolution.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalMetricsTest {

    @Test
    void computesRecallMrrNdcg() {
        List<String> predicted = List.of("a", "b", "c", "d");
        List<String> relevant = List.of("b", "d");

        assertThat(RetrievalMetrics.recallAt(predicted, relevant, 1)).isEqualTo(0.0);
        assertThat(RetrievalMetrics.recallAt(predicted, relevant, 4)).isEqualTo(1.0);
        assertThat(RetrievalMetrics.mrrAt10(predicted, relevant)).isEqualTo(0.5);
        assertThat(RetrievalMetrics.ndcgAt10(predicted, relevant)).isGreaterThan(0.0);
    }
}
