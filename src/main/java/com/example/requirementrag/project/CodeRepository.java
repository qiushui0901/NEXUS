package com.example.requirementrag.project;

/** 可独立同步和索引的代码仓库；公共库没有唯一业务项目所有者。 */
public record CodeRepository(
        String id,
        String name,
        Kind kind,
        String businessProjectId,
        String side,
        String codeCollection,
        String repositoryPath,
        String gitPath,
        String versionSourceType,
        String versionSourcePath,
        boolean liveAlias,
        boolean enabled,
        String createdAt,
        String updatedAt
) {
    public enum Kind { PROJECT, SHARED }
}
