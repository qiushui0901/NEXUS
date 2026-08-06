package com.example.requirementrag.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 配置绑定：负责加载 {@link McpProperties}（app.mcp 配置段）。
 * <p>
 * Spring AI 1.x 的 Streamable HTTP 传输由框架自动装配（endpoint 走
 * {@code spring.ai.mcp.server.streamable-http} 配置），端点鉴权与用户上下文
 * 由 {@link McpApiKeyFilter} + {@link McpUserContextHolder} 在 HTTP 层完成。
 * </p>
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpTransportConfiguration {
}
