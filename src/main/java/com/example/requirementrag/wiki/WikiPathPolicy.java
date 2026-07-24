package com.example.requirementrag.wiki;

import java.nio.file.Path;
import java.util.regex.Pattern;

/** Centralizes identifier validation and safe path resolution for Wiki files. */
final class WikiPathPolicy {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private WikiPathPolicy() {}

    static String identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException(field + " 只能包含字母、数字、点、下划线和连字符");
        }
        return value.trim();
    }

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
