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

    @Test
    void scoresQuerySpecificChineseEvidenceAboveSharedBoilerplate() {
        SparseVectorizer vectorizer = new SparseVectorizer();
        String query = "健康检查失败几次才自动回滚";
        String boilerplate = "发布回滚流程需要记录项目版本，依赖失败时显示稳定结果。";
        String precise = "健康检查连续失败三次才触发自动回滚。";

        assertThat(vectorizer.similarity(query, precise))
                .isGreaterThan(vectorizer.similarity(query, boilerplate));
        assertThat(vectorizer.similarity("", precise)).isZero();
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
