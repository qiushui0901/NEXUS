package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 冲突分析：同来源同一事实存在多个不同值时，必须生成内部冲突而不是只取第一条。 */
class MultiSourceConflictAnalyzerTest {

    private final MultiSourceConflictAnalyzer analyzer = new MultiSourceConflictAnalyzer();

    @Test
    void sameSemanticSourceMultipleValuesProduceInternalConflict() {
        UnifiedKnowledgeClaim a = semantic("c1", "成长基金", "cooldown", "30秒");
        UnifiedKnowledgeClaim b = semantic("c2", "成长基金", "cooldown", "60秒");

        List<String> conflicts = analyzer.analyze(List.of(a, b));

        assertThat(conflicts).anySatisfy(conflict -> {
            assertThat(conflict).contains("VERSION_INTERNAL");
            assertThat(conflict).contains("REQUIREMENT_SEMANTIC");
            assertThat(conflict).contains("30秒");
            assertThat(conflict).contains("60秒");
        });
        assertThat(analyzer.conflictGroups(List.of(a, b))).contains("成长基金|cooldown");
    }

    @Test
    void sameValueDuplicatesDoNotCreateConflict() {
        UnifiedKnowledgeClaim a = semantic("c1", "成长基金", "cooldown", "30秒");
        UnifiedKnowledgeClaim b = semantic("c2", "成长基金", "cooldown", "30秒");

        assertThat(analyzer.analyze(List.of(a, b))).isEmpty();
    }

    private UnifiedKnowledgeClaim semantic(String claimId, String subject, String predicate, String value) {
        return new UnifiedKnowledgeClaim(claimId, "p1", "5.1",
                "growth_fund.cooldown", subject, predicate, value, "TEXT", null,
                SourceType.REQUIREMENT_SEMANTIC, Authority.SECONDARY, KnowledgeStatus.EXTRACTED,
                "5.1", null, "semantic:" + claimId, subject);
    }
}
