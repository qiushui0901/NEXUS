package com.example.requirementrag.mcp;

import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.security.ApiKeyAuthenticationService;
import com.example.requirementrag.security.UnauthenticatedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * MCP 端点（/mcp）的 HTTP 层 API Key 鉴权：
 * 校验 X-API-Key 头，通过后把认证用户写入 {@link McpUserContextHolder} 供工具链使用，
 * 失败返回 401。非 MCP 路径直接放行（由既有 REST 鉴权链处理）。
 */
@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpApiKeyFilter extends OncePerRequestFilter {

    private static final String MCP_PATH = "/mcp";

    private final ApiKeyAuthenticationService authenticationService;

    public McpApiKeyFilter(ApiKeyAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith(MCP_PATH)) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            UserContext user = authenticationService.authenticate(
                    request.getHeader(ApiKeyAuthenticationService.API_KEY_HEADER));
            McpUserContextHolder.set(user);
            try {
                filterChain.doFilter(request, response);
            } finally {
                McpUserContextHolder.clear();
            }
        } catch (UnauthenticatedException exception) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid API key");
        }
    }
}
