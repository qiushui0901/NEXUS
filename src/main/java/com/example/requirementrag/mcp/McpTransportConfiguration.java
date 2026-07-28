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

/** Secures Streamable HTTP and makes the authenticated user available to tools. */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpTransportConfiguration {

    public static final String USER_CONTEXT_KEY = "nexus.user";
    private static final String LOWERCASE_API_KEY_HEADER = "x-api-key";

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
