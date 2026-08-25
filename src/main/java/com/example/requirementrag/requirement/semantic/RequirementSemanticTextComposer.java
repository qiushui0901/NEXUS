package com.example.requirementrag.requirement.semantic;

import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.StringJoiner;

/**
 * 语义向量文本生成器：把结构化标注渲染为稳定、可重复、可调试的检索文本。
 * 不把 JSON 原样转文本，同一事实不因字段顺序变化导致语义向量漂移。
 */
@Service
@ConditionalOnProperty(prefix = "app.rag.requirement-semantic", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class RequirementSemanticTextComposer {

    /** 生成面向向量检索的稳定语义文本（§9.2 固定字段顺序）。 */
    public String compose(String rawText, SemanticAnnotationResult result) {
        StringBuilder text = new StringBuilder();
        text.append("[原文]\n").append(rawText == null ? "" : rawText.trim());

        if (result == null) return text.toString();

        if (!result.entities().isEmpty()) {
            StringJoiner joiner = new StringJoiner("、");
            result.entities().forEach(entity -> joiner.add(safe(entity.name())));
            text.append("\n\n[主体]\n").append(joiner);
        }

        if (!result.conditions().isEmpty()) {
            text.append("\n\n[条件]");
            for (var condition : result.conditions()) {
                String logicalGroup = safe(condition.logicalGroup());
                text.append("\n")
                        .append(safe(condition.subject())).append(" ")
                        .append(safe(condition.field())).append(" ")
                        .append(operatorSymbol(condition.operator())).append(" ")
                        .append(safe(condition.value()))
                        .append(safe(condition.unit()));
                if (!logicalGroup.isEmpty()) {
                    text.append("（").append(logicalGroup).append("）");
                }
            }
        }

        if (!result.events().isEmpty()) {
            text.append("\n\n[事件]");
            for (var event : result.events()) {
                String line = safe(event.subject()) + " " + safe(event.event());
                if (!safe(event.object()).isEmpty()) line += " " + safe(event.object());
                if (!safe(event.result()).isEmpty()) line += " -> " + safe(event.result());
                text.append("\n").append(line);
            }
        }

        if (!result.claims().isEmpty()) {
            text.append("\n\n[事实]");
            for (var claim : result.claims()) {
                text.append("\n").append(safe(claim.factKey())).append(" = ")
                        .append(safe(claim.value())).append(safe(claim.unit()));
            }
        }

        if (!result.questionExpansions().isEmpty()) {
            text.append("\n\n[可能的问题]");
            for (var question : result.questionExpansions()) {
                text.append("\n").append(safe(question.text()));
            }
        }

        List<String> missing = result.missingContext();
        if (missing != null && !missing.isEmpty()) {
            text.append("\n\n[缺失上下文]\n").append(String.join("；", missing));
        }
        return text.toString();
    }

    /** 生成面向检索列表的结构化摘要（单行、有界）。 */
    public String summary(SemanticAnnotationResult result) {
        if (result == null) return "";
        StringJoiner joiner = new StringJoiner("；");
        if (!result.entities().isEmpty()) {
            StringJoiner names = new StringJoiner("、");
            result.entities().stream().limit(5).forEach(entity -> names.add(safe(entity.name())));
            joiner.add("主体：" + names);
        }
        if (!result.conditions().isEmpty()) {
            var condition = result.conditions().get(0);
            joiner.add("条件：" + safe(condition.subject()) + " "
                    + safe(condition.field()) + operatorSymbol(condition.operator())
                    + safe(condition.value()) + safe(condition.unit()));
        }
        if (!result.events().isEmpty()) {
            var event = result.events().get(0);
            joiner.add("事件：" + safe(event.subject()) + safe(event.event()));
        }
        if (!result.claims().isEmpty()) {
            joiner.add("事实：" + safe(result.claims().get(0).factKey())
                    + "=" + safe(result.claims().get(0).value()));
        }
        return joiner.toString();
    }

    private String operatorSymbol(String operator) {
        if (operator == null) return "";
        return switch (operator) {
            case "EQ" -> "=";
            case "NE" -> "!=";
            case "GT" -> ">";
            case "GTE" -> ">=";
            case "LT" -> "<";
            case "LTE" -> "<=";
            case "BETWEEN" -> "~";
            case "IN" -> "∈";
            case "NOT_IN" -> "∉";
            case "BEFORE" -> "早于";
            case "AFTER" -> "晚于";
            case "REQUIRES" -> "需要";
            case "FORBIDS" -> "禁止";
            default -> safe(operator);
        };
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
