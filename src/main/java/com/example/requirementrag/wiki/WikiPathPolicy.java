package com.example.requirementrag.wiki;

import java.nio.file.Path;
import java.util.regex.Pattern;

/** 集中校验 Wiki 文件标识符，并安全解析根目录之内的路径。 */
final class WikiPathPolicy {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private WikiPathPolicy() {}

    /** 校验并规范化标识符（仅字母、数字、点、下划线和连字符），非法时抛异常。 */
    static String identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException(field + " 只能包含字母、数字、点、下划线和连字符");
        }
        return value.trim();
    }

    /** 在根目录下逐段拼接并规范化路径，解析结果越出根目录时抛异常。 */
    static Path resolveBelow(Path root, String... segments) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot;
        for (String segment : segments) {
            resolved = resolved.resolve(identifier(segment, "路径标识"));
        }
        resolved = resolved.normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Wiki 路径越界");
        }
        return resolved;
    }
}
