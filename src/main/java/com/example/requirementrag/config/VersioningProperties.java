package com.example.requirementrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Filesystem location for version manifests. This store never contains vectors. */
@ConfigurationProperties("app.rag.versioning")
public record VersioningProperties(String rootPath) {
    @ConstructorBinding
    public VersioningProperties {
        rootPath = rootPath == null || rootPath.isBlank() ? "data/version-manifests" : rootPath.trim();
    }
}
