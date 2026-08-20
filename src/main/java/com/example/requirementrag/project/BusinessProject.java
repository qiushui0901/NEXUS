package com.example.requirementrag.project;

/** 业务项目：拥有共享需求、版本主仓库、Wiki 与访问控制边界。 */
public record BusinessProject(
        String id,
        String name,
        String versionAnchorRepositoryId,
        String requirementCollection,
        String requirementDocumentId,
        String requirementSnapshotNamespace,
        String wikiNamespace,
        String latestRequirementVersion,
        Status status,
        String createdAt,
        String updatedAt
) {
    public enum Status { ACTIVE, DISABLED }

    public boolean complete() {
        return text(id) && text(name) && text(versionAnchorRepositoryId)
                && text(requirementCollection) && text(requirementDocumentId)
                && text(requirementSnapshotNamespace) && text(wikiNamespace);
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }
}
