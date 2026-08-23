package com.example.requirementrag.knowledge.multisource.alignment;

import java.util.Locale;
import java.util.Set;

/** 跨源对齐命名工具：规范化、canonical key、代码/参数名后缀清洗。 */
final class AlignmentNaming {

    private AlignmentNaming() {
    }

    /** 代码/配置命名里常见的通用词，避免把类名/参数名噪声当成强匹配。 */
    private static final Set<String> STOPWORDS = Set.of(
            "index", "param", "params", "request", "response", "req", "resp",
            "config", "util", "utils", "helper", "constants", "enum", "exception",
            "test", "tests", "info", "data", "model", "dto", "vo", "entity");

    /** 规范化：小写 + 去分隔符（下划线/中划线/空白/点/括号等），用于确定性比对。 */
    static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s|｜:：（）()\\[\\]【】、，,。.;；/\\\\_\\-]+", "");
    }

    /** 规范化的名称包含匹配（双向包含），通用词被清洗掉。 */
    static boolean namesRelated(String left, String right) {
        if (left == null || right == null) return false;
        String a = normalize(left);
        String b = normalize(right);
        if (a.isBlank() || b.isBlank()) return false;
        if (a.equals(b)) return true;
        if (!(a.contains(b) || b.contains(a))) return false;
        String shorter = a.length() <= b.length() ? a : b;
        return !STOPWORDS.contains(shorter);
    }

    /** 从规范化名生成稳定 canonical key 段。 */
    static String keySegment(String value) {
        return normalize(value);
    }
}