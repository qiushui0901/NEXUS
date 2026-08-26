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
     *
     * <p>{@code CLAIM_VECTOR} 是 Claim 向量投影检索来源（0.9.6），
     * 与 REQUIREMENT_SEMANTIC 平级进入所有非 DOUBT 意图——
     * 向量召回补充结构化检索的语义缺口，但 DOUBT 意图只查存疑事实不查语义近似。</p>
     */
    public Set<SourceType> allowedSources(KnowledgeQueryIntent intent) {
        return switch (intent) {
            case NORMATIVE -> set(SourceType.REQUIREMENT, SourceType.PARAMETER_TABLE, SourceType.TEST_CASE,
                    SourceType.REQUIREMENT_SEMANTIC, SourceType.CLAIM_VECTOR);
            case VALIDATION -> set(SourceType.TEST_CASE, SourceType.TEST_RESULT, SourceType.REQUIREMENT,
                    SourceType.REQUIREMENT_SEMANTIC, SourceType.CLAIM_VECTOR);
            case PARAMETER -> set(SourceType.PARAMETER_TABLE, SourceType.REQUIREMENT, SourceType.TEST_CASE,
                    SourceType.REQUIREMENT_SEMANTIC, SourceType.CLAIM_VECTOR);
            case DOUBT -> set(SourceType.DOUBT, SourceType.REQUIREMENT, SourceType.TEST_CASE);
            case CONSISTENCY -> set(SourceType.REQUIREMENT, SourceType.PARAMETER_TABLE, SourceType.TEST_CASE,
                    SourceType.TEST_RESULT, SourceType.CODE, SourceType.DOUBT, SourceType.REQUIREMENT_SEMANTIC,
                    SourceType.CLAIM_VECTOR);
            case IMPACT -> set(SourceType.REQUIREMENT, SourceType.PARAMETER_TABLE, SourceType.TEST_CASE,
                    SourceType.REQUIREMENT_SEMANTIC, SourceType.CLAIM_VECTOR);
            case GENERAL -> set(SourceType.REQUIREMENT, SourceType.PARAMETER_TABLE, SourceType.TEST_CASE,
                    SourceType.TEST_RESULT, SourceType.DOUBT, SourceType.REQUIREMENT_SEMANTIC, SourceType.CLAIM_VECTOR);
        };
    }

    private Set<SourceType> set(SourceType... values) {
        return new LinkedHashSet<>(List.of(values));
    }
}