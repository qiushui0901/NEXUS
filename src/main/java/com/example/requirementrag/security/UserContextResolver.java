package com.example.requirementrag.security;

import com.example.requirementrag.config.AuthProperties;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.model.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 统一身份解析：生产环境 fail-closed。
 * <ul>
 *   <li>配置了可信网关身份头时，必须携带该头（缺失 → {@link UnauthenticatedException}），
 *       以头值作为用户名构造普通用户上下文；</li>
 *   <li>未配置身份头且允许默认管理员时（仅限本地开发）→ 默认管理员；</li>
 *   <li>未配置身份头且禁止默认管理员时 → 401（应用端口直连不能绕过认证）。</li>
 * </ul>
 */
@Component
public class UserContextResolver {

    private final AuthProperties properties;

    public UserContextResolver(AuthProperties properties) {
        this.properties = properties;
    }

    /** 从请求解析用户；无法建立可信身份时抛出 {@link UnauthenticatedException}。 */
    public UserContext resolve(HttpServletRequest request) {
        String identityHeader = properties.identityHeader();
        if (identityHeader != null) {
            String identity = request.getHeader(identityHeader);
            if (identity == null || identity.isBlank()) {
                throw new UnauthenticatedException("缺少可信网关身份头: " + identityHeader);
            }
            return new UserContext(identity.trim(), UserRole.DEVELOPER, java.util.List.of("*"));
        }
        if (!properties.defaultAdminAllowed()) {
            throw new UnauthenticatedException("应用未启用身份验证且默认管理员被禁止（生产环境需配置可信网关身份头）");
        }
        return UserContext.defaultAdmin();
    }

    /** 无请求上下文的解析（如 MCP ThreadLocal 兜底）：允许默认管理员时返回，否则抛出。 */
    public UserContext resolveFallback() {
        if (!properties.defaultAdminAllowed()) {
            throw new UnauthenticatedException("应用未启用身份验证且默认管理员被禁止");
        }
        return UserContext.defaultAdmin();
    }
}
