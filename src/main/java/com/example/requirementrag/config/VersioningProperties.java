package com.example.requirementrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 版本化管理配置，绑定 app.rag.versioning 前缀：
 * 可审阅的版本清单与需求快照（非向量化）的落盘根路径。
 */
@ConfigurationProperties("app.rag.versioning")
public record VersioningProperties(String rootPath, String requirementSnapshotRootPath) {
    /** 路径缺失/空白时回退默认目录并去除首尾空白。 */
    @ConstructorBinding
    public VersioningProperties {
        rootPath = rootPath == null || rootPath.isBlank() ? "data/version-manifests" : rootPath.trim();
        requirementSnapshotRootPath = requirementSnapshotRootPath == null || requirementSnapshotRootPath.isBlank()
                ? "data/requirement-snapshots" : requirementSnapshotRootPath.trim();
    }

    /** 兼容构造器：仅指定清单根路径，需求快照路径取默认值。 */
    public VersioningProperties(String rootPath) {
        this(rootPath, null);
    }
}
