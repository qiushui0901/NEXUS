package com.example.requirementrag.requirement.semantic;

import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationInput;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/** 需求语义标注 Prompt 生成与版本管理；只注入结构化元数据，不注入跨块上下文。 */
@Service
@ConditionalOnProperty(prefix = "app.rag.requirement-semantic", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class RequirementSemanticPromptService {
    /** Prompt 版本随语义契约演进；任何输出结构变化都必须提升版本，幂等键依赖该值。 */
    private final String promptVersion;

    public RequirementSemanticPromptService(RequirementSemanticProperties properties) {
        this.promptVersion = properties.promptVersion();
    }

    public String promptVersion() {
        return promptVersion;
    }

    public String systemPrompt() {
        return """
                你是需求语义标注器。只从给定需求 Chunk 原文中抽取可检索的结构化语义，不补充外部常识，不推断未写出的事实。
                约束：
                1. 只能根据输入 Chunk 提取事实，不得补造外部知识。
                2. 每条实体、条件、事件、数值和 Claim 必须提供 evidenceQuote，且必须是输入原文的连续子串。
                3. 如果主体、条件、数值或单位在 Chunk 中没有出现，放入 missingContext，不要猜测。
                4. certainty=EXPLICIT 仅用于原文明确表达；DERIVED 只能用于同一 Chunk 内可直接组合的事实；INFERRED 不能自动当成确认事实。
                5. 数值必须保留原始 value，同时输出 normalizedValue；单位保留原始 unit。
                6. 不要输出不存在于输入文本中的别名。
                7. 不要把问题文本本身当成确认事实。
                8. factKey 使用受控小写下划线格式，例如 growth_fund.unlock.min_level。
                9. 没有可抽取事实时返回空数组，不要编造。
                10. 只返回 JSON，不要 Markdown，不要解释。
                certainty 只能使用：%s。
                operator 只能使用：%s。
                valueType 只能使用：%s。
                question type 只能使用：%s。
                JSON 结构：
                {"entities":[{"name":"...","type":"FEATURE","aliases":[],"certainty":"EXPLICIT","evidenceQuote":"原文"}],
                 "conditions":[{"subject":"...","field":"...","operator":"GTE","value":"30","unit":"级","valueType":"NUMBER","logicalGroup":"unlock","certainty":"EXPLICIT","evidenceQuote":"原文"}],
                 "events":[{"subject":"...","event":"...","object":"","result":"...","condition":"...","certainty":"EXPLICIT","evidenceQuote":"原文"}],
                 "numericFacts":[{"subject":"...","field":"...","value":"30","normalizedValue":30,"unit":"级","normalizedUnit":"级","operator":"GTE","certainty":"EXPLICIT","evidenceQuote":"原文"}],
                 "claims":[{"factKey":"growth_fund.unlock.min_level","subject":"...","predicate":"UNLOCK_MIN_LEVEL","value":"30","unit":"级","certainty":"EXPLICIT","evidenceQuote":"原文"}],
                 "questionExpansions":[{"text":"...","type":"CONDITION"}],
                 "uncertainties":[],"missingContext":[],"selfContained":true}
                """.formatted(
                enumNames(RequirementSemanticModels.SemanticCertainty.values()),
                enumNames(RequirementSemanticModels.SemanticOperator.values()),
                enumNames(RequirementSemanticModels.SemanticValueType.values()),
                enumNames(RequirementSemanticModels.SemanticQuestionType.values()));
    }

    public String userPrompt(SemanticAnnotationInput input) {
        return """
                Prompt 版本：%s
                来源文件：%s
                父块：%s
                父块序号：%d
                窗口：%s
                章节路径：%s
                标题：%s
                输入哈希：%s
                需求原文：
                ---
                %s
                ---
                """.formatted(promptVersion, safe(input.sourceFile()), safe(input.parentId()),
                input.parentOrder(), safe(input.windowId()), safe(input.sectionPath()), safe(input.heading()),
                safe(input.contentHash()), input.rawText());
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String enumNames(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).reduce((left, right) -> left + ", " + right).orElse("");
    }
}
