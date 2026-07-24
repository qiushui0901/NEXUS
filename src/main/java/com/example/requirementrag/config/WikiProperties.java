package com.example.requirementrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Versioned Wiki source and generated artifact locations. */
@ConfigurationProperties("app.rag.wiki")
public record WikiProperties(String rootPath, String sourcePath) {

    public WikiProperties {
        rootPath = hasText(rootPath) ? rootPath.trim() : "data/wiki";
        sourcePath = hasText(sourcePath) ? sourcePath.trim() : "data/wiki-sources";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
