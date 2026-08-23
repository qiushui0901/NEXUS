package com.example.requirementrag.knowledge.multisource;

import java.util.Locale;

/**
 * 统一 fact_key 生成器：{@code <projectId>|<businessVersion>|<module>|<normalizedSubject>|<normalizedPredicate>}。
 *
 * <p>规范化：trim + 空白折叠 + 小写；空段用空字符串占位。同一事实键跨来源可聚合/冲突分析。
 */
public final class KnowledgeFactKeyGenerator {

    private KnowledgeFactKeyGenerator() {
    }

    /** 生成确定性 fact_key。 */
    public static String generate(String projectId, String businessVersion, String module,
                                  String subject, String predicate) {
        return String.join("|",
                normalize(projectId), normalize(businessVersion), normalize(module),
                normalize(subject), normalize(predicate));
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}