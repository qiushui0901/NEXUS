package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 规则优先的查询意图分类器：为多源检索决定来源过滤与融合权重。
 * 显式的 projectId/version/来源过滤永远优先于这里的自动分类结果。
 */
@Component
public class KnowledgeQueryIntentClassifier {

    /** 分类查询文本，返回最匹配的意图；无法归类时返回 GENERAL。 */
    public KnowledgeQueryIntent classify(String query) {
        if (query == null || query.isBlank()) return KnowledgeQueryIntent.GENERAL;
        String text = query.toLowerCase(Locale.ROOT);
        if (containsAny(text, "存疑", "未确认", "待确认", "风险", "待讨论", "疑问", "doubt", "risk", "open question")) {
            return KnowledgeQueryIntent.DOUBT;
        }
        if (containsAny(text, "是否一致", "实现是否", "需求.*测试", "测试.*需求", "对比", "差异", "一致", "consisten", "match")) {
            return KnowledgeQueryIntent.CONSISTENCY;
        }
        if (containsAny(text, "测试", "覆盖", "验证", "是否通过", "通过率", "test", "coverage", "validation")) {
            return KnowledgeQueryIntent.VALIDATION;
        }
        if (containsAny(text, "多少", "上限", "下限", "阈值", "单位", "范围", "几分钟", "几秒", "多大", "parameter", "limit", "range", "unit")) {
            return KnowledgeQueryIntent.PARAMETER;
        }
        if (containsAny(text, "影响哪些", "影响什么", "影响范围", "修改后", "影响", "impact", "affect")) {
            return KnowledgeQueryIntent.IMPACT;
        }
        if (containsAny(text, "应该", "必须", "需求规定", "规则", "规范", "require", "rule", "spec", "must")) {
            return KnowledgeQueryIntent.NORMATIVE;
        }
        return KnowledgeQueryIntent.GENERAL;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}