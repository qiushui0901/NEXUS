package com.example.requirementrag.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 启动时校验认证配置：启用认证时至少需要一个配置完整的用户，
 * API Key 重复等缺失/歧义配置直接抛异常阻止启动。
 */
@Component
public class AuthConfigurationValidator {
    private static final Logger log = LoggerFactory.getLogger(AuthConfigurationValidator.class);

    private final AuthProperties properties;

    public AuthConfigurationValidator(AuthProperties properties) {
        this.properties = properties;
    }

    /**
     * 启动校验入口：认证关闭时仅记录安全告警；开启时要求至少一个用户，
     * 且每个用户的用户名、API Key、角色均非空，API Key 全局唯一。
     *
     * @throws IllegalStateException 用户缺失、字段不完整或 API Key 重复时
     */
    @PostConstruct
    public void validate() {
        if (!properties.enabled()) {
            log.warn("SECURITY WARNING: API authentication is disabled; requests run as the default administrator. "
                    + "Use this setting only in an explicitly local development profile.");
            return;
        }
        if (properties.users().isEmpty()) {
            throw new IllegalStateException("Authentication is enabled but no users are configured");
        }

        Set<String> apiKeys = new HashSet<>();
        for (int index = 0; index < properties.users().size(); index++) {
            AuthProperties.AuthUser user = properties.users().get(index);
            if (user == null || blank(user.username()) || blank(user.apiKey()) || user.role() == null) {
                throw new IllegalStateException("Authentication user " + (index + 1)
                        + " must define non-blank username/api-key and role");
            }
            String key = user.apiKey().trim();
            if (!apiKeys.add(key)) {
                throw new IllegalStateException("Authentication API keys must be unique");
            }
        }
    }

    /** 判断字符串是否为 null 或空白。 */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
