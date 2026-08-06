package com.example.requirementrag.mcp;

import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.security.UnauthenticatedException;

/**
 * 承载 MCP 请求线程的认证用户上下文。
 * Spring AI 1.x 的工具方法不携带请求上下文，鉴权在 HTTP Filter 层完成，
 * 认证后的用户经此 ThreadLocal 传递给工具调用链。
 */
public final class McpUserContextHolder {

    private static final ThreadLocal<UserContext> HOLDER = new ThreadLocal<>();

    private McpUserContextHolder() {
    }

    /** 设置当前线程的认证用户（Filter 层调用）。 */
    public static void set(UserContext user) {
        HOLDER.set(user);
    }

    /** 取回当前线程的认证用户；未设置时按未认证处理。 */
    public static UserContext get() {
        UserContext user = HOLDER.get();
        if (user == null) {
            throw new UnauthenticatedException();
        }
        return user;
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
