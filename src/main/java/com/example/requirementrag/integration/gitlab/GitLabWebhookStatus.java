package com.example.requirementrag.integration.gitlab;

/** 最近一次 GitLab Webhook 接收结果，不保存请求正文或 Secret。 */
public record GitLabWebhookStatus(
        String projectId,
        String status,
        String eventId,
        String targetSha,
        String message,
        String receivedAt
) {
}
