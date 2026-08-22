package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 来源过滤策略：按查询意图决定应参与多源检索的来源类型。
 */
@Component
public class SourceFilterStrategy {

    /** 返回该意图允许的来源类型集合。 */
    public Set<SourceType> allowedSources(KnowledgeQueryIntent intent) {
        return switch (intent) {
            case NORMATIVE -> set(SourceType.REQUIREMENT, SourceType.PARAMETER_TABLE, SourceType.TEST_CASE);
            case VALIDATION -> set(SourceType.TEST_CASE, SourceType.TEST_RESULT, SourceType.REQUIREMENT);
            case PARAMETER -> set(SourceType.PARAMETER_TABLE, SourceType.REQUIREMENT, SourceType.TEST_CASE);
            case DOUBT -> set(SourceType.DOUBT, SourceType.REQUIREMENT, SourceType.TEST_CASE);
            case CONSISTENCY -> set(SourceType.REQUIREMENT, SourceType.PARAMETER_TABLE, SourceType.TEST_CASE,
                    SourceType.TEST_RESULT, SourceType.CODE, SourceType.DOUBT);
            case IMPACT -> set(SourceType.REQUIREMENT, SourceType.PARAMETER_TABLE, SourceType.TEST_CASE);
            case GENERAL -> set(SourceType.REQUIREMENT, SourceType.PARAMETER_TABLE, SourceType.TEST_CASE,
                    SourceType.TEST_RESULT, SourceType.DOUBT);
        };
    }

    private Set<SourceType> set(SourceType... values) {
        return new LinkedHashSet<>(List.of(values));
    }
}