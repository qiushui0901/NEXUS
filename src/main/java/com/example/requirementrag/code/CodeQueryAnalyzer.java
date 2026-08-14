package com.example.requirementrag.code;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性代码查询解析器：从自然语言查询中提取类名、方法名、限定名与文件路径，
 * 供精确符号通道与类名限定召回使用。纯文本规则，不调用 LLM，结果稳定可复现。
 */
@Component
public class CodeQueryAnalyzer {

    /** {@code ClassName.methodName} 或 {@code ClassName#methodName} 形式的紧邻限定符号。 */
    private static final Pattern QUALIFIED_SYMBOL = Pattern.compile(
            "\\b([A-Z][A-Za-z0-9_$]*)\\s*[.#]\\s*([A-Za-z][A-Za-z0-9_$]*)");
    /** 引号包裹的短片段（中英文引号），用于在片段中提取方法名。 */
    private static final Pattern QUOTED_SPAN = Pattern.compile(
            "[\"'“”‘’]([^\"'“”‘’]{1,80})[\"'“”‘’]");
    /** 小写开头的驼峰标识符（如 queryVipShopIndex），可作为方法名候选。 */
    private static final Pattern CAMEL_METHOD = Pattern.compile(
            "\\b([a-z][a-z0-9_$]*[A-Z][A-Za-z0-9_$]*)\\b");
    /** 「在 X 中」「应召回 X 的」等类名定位句式里的 PascalCase 类名。 */
    private static final Pattern CLASS_CONTEXT = Pattern.compile(
            "在\\s*([A-Z][A-Za-z0-9_$]{2,})\\s*中|召回\\s*([A-Z][A-Za-z0-9_$]{2,})\\s*的");
    /** 查询中出现的任意 PascalCase 混合大小写标识符，作为类名兜底候选。 */
    private static final Pattern PASCAL_CASE = Pattern.compile(
            "\\b([A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+)\\b");
    /** 显式出现的仓库相对 Java 文件路径。 */
    private static final Pattern JAVA_PATH = Pattern.compile(
            "\\b((?:[\\w.-]+/)+[\\w.-]+\\.java)\\b");

    /**
     * 解析查询。
     *
     * @return 解析结果；无任何结构化信号时为 {@link ParsedCodeQuery#GENERIC}
     */
    public ParsedCodeQuery parse(String query) {
        if (query == null || query.isBlank()) {
            return ParsedCodeQuery.GENERIC;
        }

        String className = null;
        String symbolName = null;

        Matcher qualified = QUALIFIED_SYMBOL.matcher(query);
        if (qualified.find()) {
            className = qualified.group(1);
            symbolName = qualified.group(2);
        }

        if (className == null) {
            className = contextClassName(query);
        }
        if (className == null) {
            className = fallbackClassName(query, symbolName);
        }
        if (symbolName == null && className != null) {
            symbolName = methodNameCandidate(query, className);
        }
        String filePath = filePath(query);

        if (className == null && symbolName == null && filePath == null) {
            return ParsedCodeQuery.GENERIC;
        }
        QueryKind kind = className != null && symbolName != null
                ? QueryKind.EXACT_SYMBOL
                : QueryKind.CLASS_SCOPED;
        String qualifiedName = className != null && symbolName != null ? className + "." + symbolName : null;
        return new ParsedCodeQuery(kind, className, symbolName, qualifiedName, filePath);
    }

    /** 提取引号片段内或查询正文里的小写开头驼峰标识符作为方法名候选；纯大写业务词（如 VIP）与类名本体不参与。 */
    private String methodNameCandidate(String query, String className) {
        for (Matcher span = QUOTED_SPAN.matcher(query); span.find(); ) {
            String candidate = camelMethodIn(span.group(1));
            if (candidate != null && !candidate.equals(className)) {
                return candidate;
            }
        }
        return camelMethodIn(query);
    }

    /** 在给定文本中查找第一个小写开头驼峰标识符，并排除类名本体。 */
    private String camelMethodIn(String text) {
        Matcher matcher = CAMEL_METHOD.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (candidate.length() >= 3 && !isCommonEnglishWord(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 提取「在 X 中」「应召回 X 的」句式里的类名。 */
    private String contextClassName(String query) {
        Matcher matcher = CLASS_CONTEXT.matcher(query);
        while (matcher.find()) {
            String candidate = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (hasLowercase(candidate) && !isCommonEnglishWord(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 兜底：查询中最后一个混合大小写的 PascalCase 标识符（排除方法名候选本身）。 */
    private String fallbackClassName(String query, String symbolName) {
        List<String> candidates = new ArrayList<>();
        Matcher matcher = PASCAL_CASE.matcher(query);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (candidate.equals(symbolName) || isCommonEnglishWord(candidate)) {
                continue;
            }
            candidates.add(candidate);
        }
        return candidates.isEmpty() ? null : candidates.get(candidates.size() - 1);
    }

    /** 提取显式 Java 文件路径。 */
    private String filePath(String query) {
        Matcher matcher = JAVA_PATH.matcher(query);
        return matcher.find() ? matcher.group(1) : null;
    }

    private boolean hasLowercase(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isLowerCase(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    /** 过滤「需求/方法/代码/查询」等会被 PascalCase 误抓的普通英文词。 */
    private boolean isCommonEnglishWord(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return Set.of("java", "kotlin", "class", "method", "service", "code").contains(lower);
    }

    /** 查询类型：精确符号（类名+方法名齐备）、类名限定（仅类名）、通用。 */
    public enum QueryKind { EXACT_SYMBOL, CLASS_SCOPED, GENERIC }

    /** 解析结果：类型 + 各结构化信号（缺失时为 null）。 */
    public record ParsedCodeQuery(
            QueryKind kind,
            String className,
            String symbolName,
            String qualifiedName,
            String filePath
    ) {
        public static final ParsedCodeQuery GENERIC =
                new ParsedCodeQuery(QueryKind.GENERIC, null, null, null, null);

        /** 是否存在可用于类名限定召回的类名信号。 */
        public boolean hasClassName() {
            return className != null && !className.isBlank();
        }
    }
}
