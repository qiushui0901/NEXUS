package com.example.requirementrag.web;

import com.example.requirementrag.code.CodeIndexJobService;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.GitLabPushEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Git webhook 接口：接收 GitLab push 事件并提交后台代码索引任务。
 * 任务统一走 {@link CodeIndexJobService}（同项目同时只运行一个索引任务），
 * 不再自行创建线程。
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private final ProjectRegistry projectRegistry;
    private final CodeIndexJobService codeIndexJobService;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;

    public WebhookController(ProjectRegistry projectRegistry,
                             CodeIndexJobService codeIndexJobService,
                             ObjectMapper objectMapper,
                             @Value("${webhook.secret:}") String webhookSecret) {
        this.projectRegistry = projectRegistry;
        this.codeIndexJobService = codeIndexJobService;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
    }

    /** 接收 GitLab push 事件：校验 HMAC 签名后按 Git 路径解析项目并提交后台代码索引任务。对应 POST /api/webhooks/gitlab。 */
    @PostMapping("/gitlab")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> gitlabPush(
            @RequestHeader(value = "X-Gitlab-Signature-256", required = false) String signature,
            @RequestBody byte[] rawBody) throws java.io.IOException {
        validateHmacSha256(rawBody, signature);
        GitLabPushEvent event = objectMapper.readValue(rawBody, GitLabPushEvent.class);
        if (event.project() == null || event.project().pathWithNamespace() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 project.path_with_namespace");
        }
        String projectId = projectRegistry.resolveProjectIdByGitPath(event.project().pathWithNamespace())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未知 Git 项目"));
        codeIndexJobService.start(projectId);
        return Map.of("status", "accepted", "projectId", projectId);
    }

    /** 校验 webhook 签名：secret 未配置、签名缺失或摘要不匹配均返回 401。 */
    private void validateHmacSha256(byte[] body, String signature) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook secret 未配置");
        }
        if (signature == null || !signature.startsWith("sha256=")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook 签名无效");
        }
        byte[] expected = hmacSha256(webhookSecret, body);
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signature.substring(7));
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook 签名无效");
        }
        if (!MessageDigest.isEqual(expected, provided)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook 签名无效");
        }
    }

    /** 使用 HmacSHA256 计算请求体的消息摘要。 */
    private byte[] hmacSha256(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        }
        catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC 初始化失败", exception);
        }
    }
}
