package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SourceFilterStrategyTest {

    private final SourceFilterStrategy strategy = new SourceFilterStrategy();

    @Test
    void claimVectorPresentInAllNonDoubtIntents() {
        assertThat(strategy.allowedSources(KnowledgeQueryIntent.NORMATIVE)).contains(SourceType.CLAIM_VECTOR);
        assertThat(strategy.allowedSources(KnowledgeQueryIntent.VALIDATION)).contains(SourceType.CLAIM_VECTOR);
        assertThat(strategy.allowedSources(KnowledgeQueryIntent.PARAMETER)).contains(SourceType.CLAIM_VECTOR);
        assertThat(strategy.allowedSources(KnowledgeQueryIntent.CONSISTENCY)).contains(SourceType.CLAIM_VECTOR);
        assertThat(strategy.allowedSources(KnowledgeQueryIntent.IMPACT)).contains(SourceType.CLAIM_VECTOR);
        assertThat(strategy.allowedSources(KnowledgeQueryIntent.GENERAL)).contains(SourceType.CLAIM_VECTOR);
    }

    @Test
    void claimVectorExcludedFromDoubtIntent() {
        Set<SourceType> doubtSources = strategy.allowedSources(KnowledgeQueryIntent.DOUBT);
        assertThat(doubtSources).doesNotContain(SourceType.CLAIM_VECTOR);
        assertThat(doubtSources).doesNotContain(SourceType.REQUIREMENT_SEMANTIC);
    }

    @Test
    void requirementSemanticStillPresentInNonDoubtIntents() {
        assertThat(strategy.allowedSources(KnowledgeQueryIntent.NORMATIVE)).contains(SourceType.REQUIREMENT_SEMANTIC);
        assertThat(strategy.allowedSources(KnowledgeQueryIntent.GENERAL)).contains(SourceType.REQUIREMENT_SEMANTIC);
    }
}
