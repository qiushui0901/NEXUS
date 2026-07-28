package com.example.requirementrag.config;

import com.example.requirementrag.model.UserRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthConfigurationValidatorTest {
    @Test
    void rejectsEnabledAuthenticationWithoutUsers() {
        assertThatThrownBy(() -> validator(true, List.of()).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no users");
    }

    @Test
    void rejectsEnabledAuthenticationWithBlankCredential() {
        var user = new AuthProperties.AuthUser("admin", " ", UserRole.SUPER_ADMIN, List.of("*"));
        assertThatThrownBy(() -> validator(true, List.of(user)).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("username/api-key");
    }

    @Test
    void rejectsDuplicateApiKeys() {
        var first = new AuthProperties.AuthUser("first", "same-key", UserRole.DEVELOPER, List.of("a"));
        var second = new AuthProperties.AuthUser("second", "same-key", UserRole.READONLY, List.of("a"));
        assertThatThrownBy(() -> validator(true, List.of(first, second)).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unique");
    }

    @Test
    void acceptsEnabledAuthenticationWithValidCredential() {
        var user = new AuthProperties.AuthUser("admin", "secret", UserRole.SUPER_ADMIN, List.of("*"));
        assertThatCode(() -> validator(true, List.of(user)).validate()).doesNotThrowAnyException();
    }

    @Test
    void permitsExplicitlyDisabledAuthentication() {
        assertThatCode(() -> validator(false, List.of()).validate()).doesNotThrowAnyException();
    }

    private AuthConfigurationValidator validator(boolean enabled, List<AuthProperties.AuthUser> users) {
        return new AuthConfigurationValidator(new AuthProperties(enabled, users));
    }
}
