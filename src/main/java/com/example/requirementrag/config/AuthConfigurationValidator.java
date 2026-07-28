package com.example.requirementrag.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/** Fails startup when enabled authentication has missing or ambiguous credentials. */
@Component
public class AuthConfigurationValidator {
    private static final Logger log = LoggerFactory.getLogger(AuthConfigurationValidator.class);

    private final AuthProperties properties;

    public AuthConfigurationValidator(AuthProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validate() {
        if (!properties.enabled()) {
            log.warn("SECURITY WARNING: API authentication is disabled; requests run as the default administrator. "
                    + "Use this setting only in an explicitly local development profile.");
            return;
        }
        if (properties.users().isEmpty()) {
            throw new IllegalStateException("Authentication is enabled but no users are configured");
        }

        Set<String> apiKeys = new HashSet<>();
        for (int index = 0; index < properties.users().size(); index++) {
            AuthProperties.AuthUser user = properties.users().get(index);
            if (user == null || blank(user.username()) || blank(user.apiKey()) || user.role() == null) {
                throw new IllegalStateException("Authentication user " + (index + 1)
                        + " must define non-blank username/api-key and role");
            }
            String key = user.apiKey().trim();
            if (!apiKeys.add(key)) {
                throw new IllegalStateException("Authentication API keys must be unique");
            }
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
