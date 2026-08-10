package com.example.requirementrag.mcp;

import com.example.requirementrag.security.UnauthenticatedException;
import com.example.requirementrag.security.UserContextResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * MCP 请求的身份过滤器：生产环境 fail-closed。
 * 从请求解析可信身份并写入 {@link McpUserContextHolder}；
 * 无法建立身份（直连、缺网关头、默认管理员被禁）时返回 401。
 */
@Component
public class McpIdentityFilter extends OncePerRequestFilter {

    private final UserContextResolver userContextResolver;

    public McpIdentityFilter(UserContextResolver userContextResolver) {
        this.userContextResolver = userContextResolver;
        McpUserContextHolder.registerResolver(userContextResolver);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            McpUserContextHolder.set(userContextResolver.resolve(request));
        } catch (UnauthenticatedException exception) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, exception.getMessage());
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            McpUserContextHolder.clear();
        }
    }
}
