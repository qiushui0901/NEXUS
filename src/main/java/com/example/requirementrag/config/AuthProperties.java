package com.example.requirementrag.config;

import com.example.requirementrag.model.UserRole;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 认证配置，绑定 app.rag.auth 前缀。
 * enabled 控制是否启用 API 认证；users 为可登录用户列表，未配置时为默认空列表。
 */
@ConfigurationProperties("app.rag.auth")
public record AuthProperties(boolean enabled, List<AuthUser> users) {

    public AuthProperties {
        users = users == null ? List.of() : users;
    }

    /** 单个认证用户：用户名、API Key、角色及可访问项目列表（空列表表示全部项目）。 */
    public record AuthUser(String username, String apiKey, UserRole role, List<String> projects) {
        public AuthUser {
            projects = projects == null ? List.of() : projects;
        }
    }
}
