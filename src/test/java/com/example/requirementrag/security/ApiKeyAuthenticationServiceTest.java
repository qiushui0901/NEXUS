package com.example.requirementrag.security;

import com.example.requirementrag.config.AuthProperties;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.model.UserRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiKeyAuthenticationServiceTest {

    @Test
    void resolvesConfiguredUserWithoutExposingCredential() {
        ApiKeyAuthenticationService service = service(true);

        UserContext user = service.authenticate(" dev-key ");

        assertEquals("dev", user.username());
        assertEquals(UserRole.DEVELOPER, user.role());
        assertEquals(List.of("project-a"), user.projects());
    }

    @Test
    void rejectsMissingAndInvalidKeysWithSamePublicMessage() {
        ApiKeyAuthenticationService service = service(true);

        UnauthenticatedException missing = assertThrows(UnauthenticatedException.class,
                () -> service.authenticate(null));
        UnauthenticatedException invalid = assertThrows(UnauthenticatedException.class,
                () -> service.authenticate("wrong"));

        assertEquals(missing.getMessage(), invalid.getMessage());
    }

    @Test
    void disabledAuthenticationUsesLocalDefaultAdministrator() {
        UserContext user = service(false).authenticate(null);

        assertEquals("system", user.username());
        assertEquals(UserRole.SUPER_ADMIN, user.role());
    }

    private ApiKeyAuthenticationService service(boolean enabled) {
        return new ApiKeyAuthenticationService(new AuthProperties(enabled, List.of(
                new AuthProperties.AuthUser("dev", "dev-key", UserRole.DEVELOPER, List.of("project-a")))));
    }
}
