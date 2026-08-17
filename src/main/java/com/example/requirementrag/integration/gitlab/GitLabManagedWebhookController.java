package com.example.requirementrag.integration.gitlab;

import com.example.requirementrag.model.GitLabPushEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** 项目级 GitLab Push Hook，使用 GitLab 原生 X-Gitlab-Token 并做事件幂等。 */
@RestController
@RequestMapping("/api/webhooks/gitlab")
@ConditionalOnProperty(name = "app.rag.gitlab.enabled", havingValue = "true")
public class GitLabManagedWebhookController {

    private final GitLabSyncService service;
    private final ObjectMapper objectMapper;

    public GitLabManagedWebhookController(GitLabSyncService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{projectId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> push(
            @PathVariable String projectId,
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
            @RequestHeader(value = "X-Gitlab-Event", required = false) String eventType,
            @RequestHeader(value = "X-Gitlab-Event-UUID", required = false) String eventId,
            @RequestBody byte[] body) {
        if (!"Push Hook".equals(eventType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 GitLab Push Hook");
        }
        GitLabManagedProject project;
        try {
            project = service.authenticateWebhook(projectId, token);
        } catch (SecurityException exception) {
            record(projectId, "TOKEN_MISMATCH", eventId, null, "Webhook Token 校验失败");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook token 无效");
        }
        GitLabPushEvent event;
        try {
            event = objectMapper.readValue(body, GitLabPushEvent.class);
        } catch (IOException exception) {
            record(projectId, "INVALID_JSON", eventId, null, "Webhook JSON 格式无效");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook JSON 格式无效");
        }
        if (!("refs/heads/" + project.branch()).equals(event.ref())) {
            record(projectId, "IGNORED_BRANCH", eventId, event.after(), "非目标分支，已忽略");
            return Map.of("status", "ignored", "projectId", projectId);
        }
        if (event.project() == null || !project.gitPath().equals(event.project().pathWithNamespace())) {
            record(projectId, "PROJECT_MISMATCH", eventId, event.after(), "Webhook 项目不匹配");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook 项目与接入配置不匹配");
        }
        String id = eventId == null || eventId.isBlank() ? sha256(body) : eventId.trim();
        boolean accepted = service.acceptPush(projectId, id, event.before(), event.after());
        record(projectId, accepted ? "ACCEPTED" : "DUPLICATE", id, event.after(),
                accepted ? "Webhook 已接收并进入同步队列" : "重复 Webhook 已忽略");
        return Map.of("status", accepted ? "accepted" : "duplicate", "projectId", projectId);
    }

    private void record(String projectId, String status, String eventId, String targetSha,
                        String message) {
        try {
            service.recordWebhookStatus(projectId, status, eventId, targetSha, message);
        } catch (RuntimeException ignored) {
            // 状态记录是旁路诊断，不改变 Webhook 的原始处理结果。
        }
    }

    private String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
