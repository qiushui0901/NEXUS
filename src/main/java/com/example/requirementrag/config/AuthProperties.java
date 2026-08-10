package com.example.requirementrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 认证边界配置：外部网关身份头与默认管理员兜底的开关（生产必须 fail-closed）。 */
@ConfigurationProperties(prefix = "app.rag.auth")
public record AuthProperties(
        /** 可信网关身份头名（如 X-Gateway-User）；为空表示不校验身份头。 */
        String identityHeader,
        /** 未配置身份头时是否允许默认管理员兜底；生产环境必须为 false。 */
        boolean defaultAdminAllowed
) {
    public AuthProperties {
        identityHeader = identityHeader == null || identityHeader.isBlank() ? null : identityHeader.trim();
    }
}
