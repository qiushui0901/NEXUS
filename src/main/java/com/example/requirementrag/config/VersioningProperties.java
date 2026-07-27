package com.example.requirementrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Filesystem locations for reviewable version manifests and non-vector requirement snapshots. */
@ConfigurationProperties("app.rag.versioning")
public record VersioningProperties(String rootPath, String requirementSnapshotRootPath) {
    @ConstructorBinding
    public VersioningProperties {
        rootPath = rootPath == null || rootPath.isBlank() ? "data/version-manifests" : rootPath.trim();
        requirementSnapshotRootPath = requirementSnapshotRootPath == null || requirementSnapshotRootPath.isBlank()
                ? "data/requirement-snapshots" : requirementSnapshotRootPath.trim();
    }

    public VersioningProperties(String rootPath) {
        this(rootPath, null);
    }
}
