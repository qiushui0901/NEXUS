package com.example.requirementrag.versioning;

import java.nio.file.Path;
import java.util.regex.Pattern;

/** 校验清单标识符，并确保所有解析出的路径都位于配置根目录之下。 */
final class VersionPathPolicy {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private VersionPathPolicy() {}

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
            throw new IllegalArgumentException("版本档案路径无效");
        }
        return resolved;
    }
}
