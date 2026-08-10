package com.example.requirementrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 认证边界配置：外部网关身份头、角色头、受信来源与默认管理员兜底（生产必须 fail-closed）。 */
@ConfigurationProperties(prefix = "app.rag.auth")
public record AuthProperties(
        /** 可信网关身份头名（如 X-Gateway-User）；为空表示不校验身份头。 */
        String identityHeader,
        /** 网关角色头名（如 X-Gateway-Role）；缺失或非法时身份用户按最小权限 READONLY 处理。 */
        String roleHeader,
        /** 受信来源（IP 前缀 / CIDR，逗号分隔）；非空时只接受这些来源的身份头，其他来源一律 401。 */
        String trustedSources,
        /** 未配置身份头时是否允许默认管理员兜底；生产环境必须为 false。 */
        boolean defaultAdminAllowed
) {
    public AuthProperties {
        identityHeader = identityHeader == null || identityHeader.isBlank() ? null : identityHeader.trim();
        roleHeader = roleHeader == null || roleHeader.isBlank() ? null : roleHeader.trim();
        trustedSources = trustedSources == null || trustedSources.isBlank() ? null : trustedSources.trim();
    }
}
