package com.example.requirementrag.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserContextTest {

    @Test
    void superAdminHasAllPermissions() {
        UserContext user = new UserContext("admin", UserRole.SUPER_ADMIN, List.of("*"));
        for (Permission permission : Permission.values()) {
            assertTrue(user.hasPermission(permission));
        }
    }

    @Test
    void developerCanReadAndOperateButNotWrite() {
        UserContext user = new UserContext("dev", UserRole.DEVELOPER, List.of("fengshen-server"));
        assertTrue(user.hasPermission(Permission.PUBLIC_READ));
        assertTrue(user.hasPermission(Permission.OPERATE));
        assertFalse(user.hasPermission(Permission.WRITE));
        assertFalse(user.hasPermission(Permission.ADMIN));
    }

    @Test
    void readonlyCanOnlyRead() {
        UserContext user = new UserContext("viewer", UserRole.READONLY, List.of("fengshen-server"));
        assertTrue(user.hasPermission(Permission.PUBLIC_READ));
        assertFalse(user.hasPermission(Permission.OPERATE));
        assertFalse(user.hasPermission(Permission.WRITE));
    }

    @Test
    void superAdminBypassesProjectList() {
        UserContext user = new UserContext("admin", UserRole.SUPER_ADMIN, List.of());
        assertTrue(user.hasAccessTo("any-project"));
    }

    @Test
    void projectScopedUserDeniedForOtherProject() {
        UserContext user = new UserContext("dev", UserRole.DEVELOPER, List.of("project-a"));
        assertTrue(user.hasAccessTo("project-a"));
        assertFalse(user.hasAccessTo("project-b"));
    }

    @Test
    void nonAdminDeniedWhenProjectIdBlank() {
        UserContext user = new UserContext("dev", UserRole.DEVELOPER, List.of("project-a"));
        assertFalse(user.hasAccessTo(null));
        assertFalse(user.hasAccessTo(""));
    }
}
