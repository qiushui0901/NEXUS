package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;

import java.util.Optional;

/**
 * 查询意图 LLM 回退：当规则分类器无法归类（返回 GENERAL）时，可选调用 LLM 细化意图。
 *
 * <p>实现必须对所有失败降级为空（保持规则结果），不得抛出异常。
 */
public interface KnowledgeQueryIntentLlmFallback {

    /**
     * 尝试用 LLM 判断查询意图。
     *
     * @return 命中合法意图时的 Optional；无法判断或调用失败时为空
     */
    Optional<KnowledgeQueryIntent> tryClassify(String query);
}