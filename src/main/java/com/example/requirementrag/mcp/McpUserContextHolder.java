package com.example.requirementrag.mcp;

import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.security.UnauthenticatedException;
import com.example.requirementrag.security.UserContextResolver;

/**
 * 承载 MCP 请求线程的认证用户上下文。
 * Spring AI 1.x 的工具方法不携带请求上下文，鉴权在 HTTP Filter 层完成，
 * 认证后的用户经此 ThreadLocal 传递给工具调用链。
 */
public final class McpUserContextHolder {

    private static final ThreadLocal<UserContext> HOLDER = new ThreadLocal<>();
    private static volatile UserContextResolver resolver;

    private McpUserContextHolder() {
    }

    /** 注册统一身份解析器（应用启动时由配置注入）。 */
    public static void registerResolver(UserContextResolver value) {
        resolver = value;
    }

    /** 设置当前线程的认证用户（Filter 层调用）。 */
    public static void set(UserContext user) {
        HOLDER.set(user);
    }

    /** 取回当前线程的认证用户；未设置时按认证边界解析（生产 fail-closed）。 */
    public static UserContext get() {
        UserContext user = HOLDER.get();
        if (user != null) return user;
        UserContextResolver active = resolver;
        if (active != null) return active.resolveFallback();
        return UserContext.defaultAdmin();
    }

    /** 取回当前线程的认证用户；未设置时返回 null（不抛异常）。 */
    public static UserContext peek() {
        return HOLDER.get();
    }

    /** 请求结束后清除（Filter 层调用），避免线程池复用导致串号。 */
    public static void clear() {
        HOLDER.remove();
    }
}
