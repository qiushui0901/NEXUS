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

    @Test
    void codeVariantSplitsCamelCaseIdentifiersSoTermsMatchAcrossQueryAndIndex() {
        SparseVectorizer vectorizer = new SparseVectorizer();
        var sync = vectorizer.vectorizeCode("sync");
        var camel = vectorizer.vectorizeCode("syncRevocation()");

        assertThat(sync.indices()).allMatch(camel.indices()::contains);
        assertThat(vectorizer.vectorizeCode("syncUserIndex()"))
                .isEqualTo(vectorizer.vectorizeCode("sync user index"));
        assertThat(vectorizer.vectorize("syncRevocation()").indices())
                .doesNotContain(sync.indices().get(0));
    }

    @Test
    void codeVariantKeepsDocumentBehaviorUnchanged() {
        SparseVectorizer vectorizer = new SparseVectorizer();
        String chinese = "需求文档 版本隔离";

        assertThat(vectorizer.vectorizeCode(chinese)).isEqualTo(vectorizer.vectorize(chinese));
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
