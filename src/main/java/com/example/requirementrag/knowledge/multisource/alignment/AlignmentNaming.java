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

    /** 从 fact key 提取模块段（约定格式：project|version|module|subject|predicate）。 */
    static String factKeyModule(String factKey) {
        if (factKey == null) return "";
        String[] parts = factKey.split("\\|");
        return parts.length >= 3 ? parts[2].trim() : "";
    }

    /**
     * 来源特定的 module 提取（dev md §4.1 实体身份一致性）：
     * 不盲取 factKey 第三段——需求/语义来源中第三段常把 subject 同时当作 module（HTML 导入
     * {@code ImmortalKnowledgeImporter}、需求图候选 {@code RequirementGraphCandidateAdapter}），
     * 若第三段与 subject 相同则视为无模块，canonical key 退化为 subject 锚定，保证不同来源的
     * 同一需求（如“攻击力”）归一到同一实体，而非生成“攻击力.攻击力”。
     */
    static String moduleOf(com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType sourceType,
                           String factKey, String subject) {
        String raw = factKeyModule(factKey);
        if (raw.isBlank()) {
            return "";
        }
        if (sourceType == com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType.REQUIREMENT
                || sourceType == com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType.REQUIREMENT_SEMANTIC) {
            String normalizedSubject = keySegment(subject);
            if (!normalizedSubject.isBlank() && keySegment(raw).equals(normalizedSubject)) {
                return "";
            }
        }
        return raw;
    }

    /**
     * 跨版本、跨来源的稳定实体 canonical key：{@code <module>.<subject>}（无来源前缀）。
     * module 或 subject 为空时退化为另一端；两者皆空返回空串。
     */
    static String conceptKey(String module, String subject) {
        String m = keySegment(module);
        String s = keySegment(subject);
        if (s.isBlank()) return m;
        return m.isBlank() ? s : m + "." + s;
    }
}