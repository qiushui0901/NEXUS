package com.example.requirementrag.security;

import com.example.requirementrag.config.AuthProperties;
import com.example.requirementrag.model.UserContext;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** 与传输层无关的 API 密钥认证，REST 与 MCP 共用。 */
@Service
public class ApiKeyAuthenticationService {

    public static final String API_KEY_HEADER = "X-API-Key";

    private final AuthProperties properties;

    public ApiKeyAuthenticationService(AuthProperties properties) {
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * 认证 API 密钥：禁用认证时返回默认管理员；密钥缺失或与任一配置用户不匹配时抛 {@link UnauthenticatedException}。
     *
     * @param apiKey 请求携带的 API 密钥（原始头值）
     * @return 匹配到的用户上下文（用户名、角色、可用项目）
     */
    public UserContext authenticate(String apiKey) {
        if (!properties.enabled()) {
            return UserContext.defaultAdmin();
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new UnauthenticatedException();
        }
        String candidate = apiKey.trim();
        for (AuthProperties.AuthUser configured : properties.users()) {
            if (constantTimeEquals(candidate, configured.apiKey())) {
                return new UserContext(configured.username(), configured.role(), List.copyOf(configured.projects()));
            }
        }
        throw new UnauthenticatedException();
    }

    /** 常量时间比较，避免时序侧信道泄露密钥信息。 */
    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
