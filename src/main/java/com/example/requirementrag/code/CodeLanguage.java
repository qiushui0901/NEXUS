package com.example.requirementrag.code;

import java.util.Locale;

/** 代码智能索引支持的编程语言。 */
public enum CodeLanguage {
    JAVA("java"), GO("go"), PYTHON("python"), TYPESCRIPT("typescript"), KOTLIN("kotlin"), UNKNOWN("unknown");

    private final String id;

    CodeLanguage(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** 根据文件路径后缀判断语言，无法识别时返回 UNKNOWN。 */
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
