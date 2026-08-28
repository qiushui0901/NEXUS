package com.example.requirementrag.knowledge.multisource.entity;

import java.util.List;
import java.util.stream.Collectors;

/** 实体提取 Prompt：问题分析、来源提取、受限选择三套，均要求 JSON 形状并强调“只做候选”。 */
final class EntityExtractionPromptService {

    private EntityExtractionPromptService() {
    }

    static final String SYSTEM_PREFIX = """
            你是实体对齐助手。你只能基于系统提供的信息输出结构化候选，不得编造实体 ID、Claim ID、
            证据或代码位置。LLM 输出只是候选，最终以系统校验为准。""";

    static String questionSystemPrompt() {
        return SYSTEM_PREFIX + """
            \n请从用户问题中提取实体、意图与版本条件，输出 JSON：
            {"entities":[{"name":"...","aliases":[...],"type":"ATTRIBUTE|MODULE|CONFIG|CODE","confidence":0.9}],
             "intent":"GENERAL|CURRENT_STATE|NUMERIC_VALUE|IMPLEMENTATION|HISTORY|VALIDATION|CONSISTENCY",
             "versions":["5.1"]}
            注意：versions 为空数组表示不限版本；不要输出问题中不存在的版本。""";
    }

    static String questionUserPrompt(String projectId, String query, List<String> candidates) {
        return "项目: " + projectId + "\n问题: " + query
                + "\n可解析候选实体（只能从中选择，不得新增）: "
                + candidates.stream().map(c -> "- " + c).collect(Collectors.joining("\n"))
                + "\n若问题实体不在候选列表中，entities 返回空数组。";
    }

    static String sourceSystemPrompt() {
        return SYSTEM_PREFIX + """
            \n请从给定来源记录中提取实体、事实与关系，输出 JSON：
            {"entities":[{"name":"...","aliases":[...],"type":"...","description":"...","confidence":0.9}],
             "facts":[{"entityName":"...","predicate":"...","value":"...","unit":"...","sourceClaimId":"...","confidence":0.9}],
             "relations":[{"sourceEntityName":"...","targetName":"...","relationType":"SUPPORTS|VERIFIES|IMPLEMENTED_BY|RAISES_DOUBT|SUPERSEDES|REFINES|REPEALS|SAME_FACT|RELATED_TO","confidence":0.9}]}
            约束：facts.sourceClaimId 必须来自给定的来源记录；relations 两端必须来自 entities 或给定代码符号；
            不得修改来源原始值。""";
    }

    static String sourceUserPrompt(String projectId, String businessVersion, List<String> claims) {
        return "项目: " + projectId + "\n业务版本: " + businessVersion + "\n来源记录（subject/predicate/value/module）:\n"
                + String.join("\n", claims);
    }

    /** 受限选择：LLM 只能从候选 entityId 中选择，输出选中的 entityId 列表。 */
    static String selectionSystemPrompt() {
        return SYSTEM_PREFIX + """
            \n以下是一个实体的候选列表。请选择与目标描述最匹配的一个实体，输出 JSON：
            {"entityId":"选中的候选ID","confidence":0.9}
            若都不匹配，输出 {"entityId":null,"confidence":0}。""";
    }

    static String selectionUserPrompt(String target, List<String> candidates) {
        return "目标描述: " + target + "\n候选（只能选择以下 ID，不得编造）:\n"
                + candidates.stream().map(c -> "- " + c).collect(Collectors.joining("\n"));
    }
}
