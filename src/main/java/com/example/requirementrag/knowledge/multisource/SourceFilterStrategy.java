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

    /**
     * 返回该意图允许的来源类型集合。
     *
     * <p>{@code REQUIREMENT_SEMANTIC} 是 LLM 语义标注候选，进入所有非 DOUBT 意图，
     * 但 NORMATIVE（规范事实）意图下是否可见由适配器按
     * {@code app.rag.requirement-semantic.normative-retrieval-enabled} 动态门禁——
     * 语义候选未经人工审核，默认不得作为规范确认事实。</p>
     */
    public Set<SourceType> allowedSources(KnowledgeQueryIntent intent) {
        return switch (intent) {
            case NORMATIVE -> set(SourceType.REQUIREMENT, SourceType.PARAMETER_TABLE, SourceType.TEST_CASE,
                    SourceType.REQUIREMENT_SEMANTIC);
            case VALIDATION -> set(SourceType.TEST_CASE, SourceType.TEST_RESULT, SourceType.REQUIREMENT,
                    SourceType.REQUIREMENT_SEMANTIC);
            case PARAMETER -> set(SourceType.PARAMETER_TABLE, SourceType.REQUIREMENT, SourceType.TEST_CASE,
                    SourceType.REQUIREMENT_SEMANTIC);
            case DOUBT -> set(SourceType.DOUBT, SourceType.REQUIREMENT, SourceType.TEST_CASE);
            case CONSISTENCY -> set(SourceType.REQUIREMENT, SourceType.PARAMETER_TABLE, SourceType.TEST_CASE,
                    SourceType.TEST_RESULT, SourceType.CODE, SourceType.DOUBT, SourceType.REQUIREMENT_SEMANTIC);
            case IMPACT -> set(SourceType.REQUIREMENT, SourceType.PARAMETER_TABLE, SourceType.TEST_CASE,
                    SourceType.REQUIREMENT_SEMANTIC);
            case GENERAL -> set(SourceType.REQUIREMENT, SourceType.PARAMETER_TABLE, SourceType.TEST_CASE,
                    SourceType.TEST_RESULT, SourceType.DOUBT, SourceType.REQUIREMENT_SEMANTIC);
        };
    }

    private Set<SourceType> set(SourceType... values) {
        return new LinkedHashSet<>(List.of(values));
    }
}