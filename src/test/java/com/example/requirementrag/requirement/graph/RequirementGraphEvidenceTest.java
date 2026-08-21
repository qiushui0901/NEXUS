package com.example.requirementrag.requirement.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementGraphEvidenceTest {
    @Test
    void resolvesExactQuoteToAbsoluteWindowOffsets() {
        RequirementGraphEvidence.Span span = RequirementGraphEvidence.resolve("前置文本。库存需要回滚。后置文本。", "库存需要回滚", 100);

        assertThat(span.status()).isEqualTo(RequirementGraphModels.EvidenceResolutionStatus.RESOLVED);
        assertThat(span.startOffset()).isEqualTo(105);
        assertThat(span.endOffset()).isEqualTo(111);
        assertThat(span.quote()).isEqualTo("库存需要回滚");
    }

    @Test
    void marksUnknownQuoteUnavailableInsteadOfInventingOffsets() {
        RequirementGraphEvidence.Span span = RequirementGraphEvidence.resolve("库存需要回滚", "订单已完成", 0);

        assertThat(span.status()).isEqualTo(RequirementGraphModels.EvidenceResolutionStatus.UNAVAILABLE);
        assertThat(span.startOffset()).isNegative();
        assertThat(span.endOffset()).isNegative();
    }
}
