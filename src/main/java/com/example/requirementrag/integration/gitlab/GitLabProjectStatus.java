package com.example.requirementrag.integration.gitlab;

/** GitLab 托管项目的接入和索引状态。 */
public enum GitLabProjectStatus {
    PENDING,
    CLONING,
    SYNCING,
    INDEXING,
    READY,
    FAILED,
    DISABLED
}
