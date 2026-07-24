package com.example.requirementrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Versioned Wiki source, draft and generated artifact locations. */
@ConfigurationProperties("app.rag.wiki")
public record WikiProperties(String rootPath, String sourcePath, String draftPath) {

    @ConstructorBinding
    public WikiProperties {
        rootPath = hasText(rootPath) ? rootPath.trim() : "data/wiki";
        sourcePath = hasText(sourcePath) ? sourcePath.trim() : "data/wiki-sources";
        draftPath = hasText(draftPath) ? draftPath.trim() : "data/wiki-drafts";
    }

    /** Keeps existing embedded/test construction source-compatible. */
    public WikiProperties(String rootPath, String sourcePath) {
        this(rootPath, sourcePath, null);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
