package com.example.requirementrag.code;

import java.util.Locale;

/** Languages understood by the code intelligence index. */
public enum CodeLanguage {
    JAVA("java"), GO("go"), PYTHON("python"), TYPESCRIPT("typescript"), KOTLIN("kotlin"), UNKNOWN("unknown");

    private final String id;

    CodeLanguage(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static CodeLanguage fromPath(String path) {
        String value = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (value.endsWith(".java")) return JAVA;
        if (value.endsWith(".go")) return GO;
        if (value.endsWith(".py")) return PYTHON;
        if (value.endsWith(".ts") || value.endsWith(".tsx")) return TYPESCRIPT;
        if (value.endsWith(".kt") || value.endsWith(".kts")) return KOTLIN;
        return UNKNOWN;
    }
}
