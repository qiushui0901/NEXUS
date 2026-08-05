package com.example.requirementrag.mcp;

import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.security.ApiKeyAuthenticationService;
import com.example.requirementrag.security.UnauthenticatedException;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * MCP Streamable HTTP 传输层安全配置：
 * 在传输层用 API Key 校验每个请求，并把认证后的用户放入传输上下文，
 * 供后续工具/资源处理器通过 {@link #USER_CONTEXT_KEY} 取用。
 * 仅在 {@code app.mcp.enabled=true}（默认）时生效。
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpTransportConfiguration {

    /** 传输上下文中存放已认证用户对象的键 */
    public static final String USER_CONTEXT_KEY = "nexus.user";
    /** Streamable HTTP 安全校验使用的小写 x-api-key 头名 */
    private static final String LOWERCASE_API_KEY_HEADER = "x-api-key";

    /**
     * 构建并暴露 MCP 传输 Provider：接入 API Key 安全校验器，
     * 并从请求头提取认证用户放入传输上下文。
     *
     * @param jsonMapper            MCP 使用的 Jackson JSON 映射器
     * @param serverProperties      MCP Server 的 Streamable HTTP 配置（端点、保活间隔等）
     * @param authenticationService API Key 认证服务
     * @return 装配好安全与上下文的传输 Provider
     */
    @Bean
    WebMvcStreamableServerTransportProvider nexusMcpTransportProvider(
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
            McpServerStreamableHttpProperties serverProperties,
            ApiKeyAuthenticationService authenticationService) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(serverProperties.getMcpEndpoint())
                .keepAliveInterval(serverProperties.getKeepAliveInterval())
                .disallowDelete(serverProperties.isDisallowDelete())
                .securityValidator(securityValidator(authenticationService))
                .contextExtractor(request -> transportContext(request, authenticationService))
                .build();
    }

    /** 构造传输层安全校验器：校验请求头中的 API Key，失败时以 401 拒绝传输。 */
    private ServerTransportSecurityValidator securityValidator(ApiKeyAuthenticationService authenticationService) {
        return headers -> {
            try {
                authenticationService.authenticate(first(headers.get(LOWERCASE_API_KEY_HEADER)));
            }
            catch (UnauthenticatedException exception) {
                throw new ServerTransportSecurityException(401, "Missing or invalid API key");
            }
        };
    }

    /** 从请求头认证用户，并构造携带该用户的传输上下文供工具/资源取用。 */
    private McpTransportContext transportContext(ServerRequest request,
                                                 ApiKeyAuthenticationService authenticationService) {
        UserContext user = authenticationService.authenticate(
                first(request.headers().header(ApiKeyAuthenticationService.API_KEY_HEADER)));
        return McpTransportContext.create(Map.of(USER_CONTEXT_KEY, user));
    }

    private String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.getFirst();
    }
}
