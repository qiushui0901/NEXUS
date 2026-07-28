package com.example.requirementrag.security;

import com.example.requirementrag.config.AuthProperties;
import com.example.requirementrag.model.UserContext;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Transport-neutral API-key authentication shared by REST and MCP. */
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

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
