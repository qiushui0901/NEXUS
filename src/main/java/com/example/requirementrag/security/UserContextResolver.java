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
            requireTrustedSource(request);
            UserRole role = resolveRole(request);
            return new UserContext(identity.trim(), role, java.util.List.of("*"));
        }
        if (!properties.defaultAdminAllowed()) {
            throw new UnauthenticatedException("应用未启用身份验证且默认管理员被禁止（生产环境需配置可信网关身份头）");
        }
        return UserContext.defaultAdmin();
    }

    /**
     * 身份头只断言身份，不授权：角色取自可选角色头（缺失或非法 → READONLY 最小权限），
     * 写操作（OPERATE/WRITE）需要网关口显式声明角色；直连伪造身份头无法提权。
     */
    private UserRole resolveRole(HttpServletRequest request) {
        String roleHeader = properties.roleHeader();
        if (roleHeader == null) {
            return UserRole.READONLY;
        }
        String value = request.getHeader(roleHeader);
        if (value == null || value.isBlank()) {
            return UserRole.READONLY;
        }
        try {
            return UserRole.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new UnauthenticatedException("非法网关角色头值: " + roleHeader);
        }
    }

    /** 配置了受信来源时，拒绝来自其他来源的身份头请求（防应用端口直连伪造身份）。 */
    private void requireTrustedSource(HttpServletRequest request) {
        String trusted = properties.trustedSources();
        if (trusted == null) return;
        String remote = request.getRemoteAddr();
        boolean allowed = java.util.Arrays.stream(trusted.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .anyMatch(value -> matchesSource(remote, value));
        if (!allowed) {
            throw new UnauthenticatedException("请求来源不在受信列表内，拒绝身份头: " + remote);
        }
    }

    /** 支持 IP 精确（127.0.0.1）、前缀（10.0.）与 CIDR（10.0.0.0/8）三种写法。 */
    static boolean matchesSource(String remote, String rule) {
        if (remote == null || rule == null) return false;
        if (rule.indexOf('/') > 0) {
            String[] parts = rule.split("/");
            try {
                int prefix = Integer.parseInt(parts[1]);
                long ip = ipToLong(remote);
                long network = ipToLong(parts[0]);
                long mask = prefix == 0 ? 0 : 0xFFFFFFFFL << (32 - prefix);
                return (ip & mask) == (network & mask);
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return remote.equals(rule) || remote.startsWith(rule);
    }

    private static long ipToLong(String address) {
        long result = 0;
        for (String part : address.split("\\.")) {
            result = (result << 8) | (Integer.parseInt(part) & 0xFF);
        }
        return result;
    }

    /** 无请求上下文的解析（如 MCP ThreadLocal 兜底）：允许默认管理员时返回，否则抛出。 */
    public UserContext resolveFallback() {
        if (!properties.defaultAdminAllowed()) {
            throw new UnauthenticatedException("应用未启用身份验证且默认管理员被禁止");
        }
        return UserContext.defaultAdmin();
    }
}
