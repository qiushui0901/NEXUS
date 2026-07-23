package com.example.requirementrag.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SparseVectorizerTest {

    @Test
    void producesStableNormalizedChineseAndEnglishTerms() {
        SparseVectorizer vectorizer = new SparseVectorizer();
        var first = vectorizer.vectorize("同盟 cooldown 冷却时间");
        var second = vectorizer.vectorize("同盟 cooldown 冷却时间");

        assertThat(first).isEqualTo(second);
        assertThat(first.indices()).isSorted().doesNotHaveDuplicates();
        double norm = Math.sqrt(first.values().stream().mapToDouble(v -> v * v).sum());
        assertThat(norm).isCloseTo(1.0, within(0.0001));
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
