package com.example.requirementrag.config;

import com.example.requirementrag.model.UserRole;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("app.rag.auth")
public record AuthProperties(boolean enabled, List<AuthUser> users) {

    public AuthProperties {
        users = users == null ? List.of() : users;
    }

    public record AuthUser(String username, String apiKey, UserRole role, List<String> projects) {
        public AuthUser {
            projects = projects == null ? List.of() : projects;
        }
    }
}
