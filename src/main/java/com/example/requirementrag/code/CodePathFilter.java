package com.example.requirementrag.code;

import java.util.List;

/**
 * 扫描路径过滤规则（JavaCodeScanner 与 MultiLanguageCodeScanner 共用，保持语义一致）。
 *
 * <p>排除匹配语义（路径以 {@code /} 开头、仓库相对）：</p>
 * <ul>
 *   <li>路径以排除项开头（仓库根锚定，如 {@code /target/}）→ 排除；</li>
 *   <li><b>文件规则</b>（不以 {@code /} 结尾，如 {@code /简历.md}、{@code /Generated.java}）→ 子串命中即排除，
 *       源码树内同样生效；</li>
 *   <li><b>目录规则</b>（以 {@code /} 结尾）再分两类：
 *     <ul>
 *       <li>以 {@code /src/} 开头的源码树内容过滤（如 {@code /src/main/resources/}）→ 子串命中即排除；</li>
 *       <li>其余目录型排除项（如 {@code /build/}、{@code /target/}）→ 仅当命中位置不在 {@code /src/}
 *           源码树内才排除，避免误伤包目录名为 {@code build} 的源码文件；模块级构建产物目录
 *           （{@code moduleA/target/}）仍会被排除。</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>包含匹配保持原语义：包含子串命中即包含，未配置包含项时全部包含。</p>
 */
final class CodePathFilter {

    private CodePathFilter() {
    }

    /** 路径是否命中任一排除项。 */
    static boolean excluded(String relative, List<String> excludes) {
        if (excludes == null || excludes.isEmpty()) {
            return false;
        }
        for (String exclude : excludes) {
            if (exclude != null && !exclude.isBlank() && matches(relative, exclude)) {
                return true;
            }
        }
        return false;
    }

    /** 路径是否命中包含项；未配置包含项时全部包含。 */
    static boolean included(String relative, List<String> includes) {
        return includes == null || includes.isEmpty()
                || includes.stream().anyMatch(include -> include != null && relative.contains(include));
    }

    private static boolean matches(String relative, String pattern) {
        if (relative.startsWith(pattern)) {
            return true;
        }
        if (!pattern.endsWith("/")) {
            // 文件规则：子串命中即排除（源码树内的指定文件同样生效）
            return relative.contains(pattern);
        }
        if (pattern.startsWith("/src/")) {
            // 源码树内容过滤目录：子串命中即排除
            return relative.contains(pattern);
        }
        // 目录型构建产物规则：仅命中源码树外的路径段才排除
        int patternIndex = relative.indexOf(pattern);
        if (patternIndex < 0) {
            return false;
        }
        int sourceIndex = relative.indexOf("/src/");
        return sourceIndex < 0 || patternIndex < sourceIndex;
    }
}
