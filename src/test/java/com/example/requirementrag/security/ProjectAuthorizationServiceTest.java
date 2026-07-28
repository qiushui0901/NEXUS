package com.example.requirementrag.security;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.model.UserRole;
import com.example.requirementrag.web.AccessDeniedException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectAuthorizationServiceTest {

    @Test
    void resolvesDefaultProjectAndRejectsUnauthorizedScope() {
        ProjectRegistry registry = mock(ProjectRegistry.class);
        RagProperties.ProjectConfig defaultProject = mock(RagProperties.ProjectConfig.class);
        when(defaultProject.id()).thenReturn("project-a");
        when(registry.defaultProject()).thenReturn(defaultProject);
        ProjectAuthorizationService service = new ProjectAuthorizationService(registry);
        UserContext user = new UserContext("dev", UserRole.DEVELOPER, List.of("project-a"));

        assertEquals("project-a", service.requireProjectAccess(user, null));
        assertThrows(AccessDeniedException.class, () -> service.requireProjectAccess(user, "project-b"));
    }

    @Test
    void enforcesRolePermission() {
        ProjectAuthorizationService service = new ProjectAuthorizationService(mock(ProjectRegistry.class));
        UserContext viewer = new UserContext("viewer", UserRole.READONLY, List.of("*"));

        service.requirePermission(viewer, Permission.PUBLIC_READ);
        assertThrows(AccessDeniedException.class, () -> service.requirePermission(viewer, Permission.OPERATE));
    }
}
