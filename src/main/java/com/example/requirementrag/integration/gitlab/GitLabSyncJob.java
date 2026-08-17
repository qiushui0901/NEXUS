package com.example.requirementrag.integration.gitlab;

import java.util.List;

/** 可审计的 GitLab 同步任务及阶段事件。 */
public record GitLabSyncJob(
        String id,
        String projectId,
        String triggerType,
        String status,
        String phase,
        String sourceSha,
        String targetSha,
        Integer changedFiles,
        String errorCode,
        String errorMessage,
        String correlationId,
        String startedAt,
        String finishedAt,
        List<Event> events
) {
    public record Event(
            String id,
            String jobId,
            String phase,
            String status,
            String message,
            String createdAt
    ) {
    }
}
