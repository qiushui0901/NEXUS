package com.example.requirementrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Versioned Wiki source, draft and generated artifact locations. */
@ConfigurationProperties("app.rag.wiki")
public record WikiProperties(String rootPath, String sourcePath, String draftPath,
                             long cacheTtlSeconds, int cacheMaxEntries) {

    @ConstructorBinding
    public WikiProperties {
        rootPath = hasText(rootPath) ? rootPath.trim() : "data/wiki";
        sourcePath = hasText(sourcePath) ? sourcePath.trim() : "data/wiki-sources";
        draftPath = hasText(draftPath) ? draftPath.trim() : "data/wiki-drafts";
        cacheTtlSeconds = cacheTtlSeconds < 0 ? 0 : cacheTtlSeconds == 0 ? 300 : cacheTtlSeconds;
        cacheMaxEntries = cacheMaxEntries < 0 ? 0 : cacheMaxEntries == 0 ? 2_000 : cacheMaxEntries;
    }

    /** Keeps existing embedded/test construction source-compatible. */
    public WikiProperties(String rootPath, String sourcePath) {
        this(rootPath, sourcePath, null, 300, 2_000);
    }

    /** Keeps pre-0.8 draft-path construction source-compatible. */
    public WikiProperties(String rootPath, String sourcePath, String draftPath) {
        this(rootPath, sourcePath, draftPath, 300, 2_000);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
