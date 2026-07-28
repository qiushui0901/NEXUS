package com.example.requirementrag.security;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.web.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Transport-neutral permission and project-scope authorization. */
@Service
public class ProjectAuthorizationService {

    private final ProjectRegistry projectRegistry;

    public ProjectAuthorizationService(ProjectRegistry projectRegistry) {
        this.projectRegistry = projectRegistry;
    }

    public void requirePermission(UserContext user, Permission permission) {
        if (user == null || !user.hasPermission(permission)) {
            throw new AccessDeniedException("Insufficient permissions");
        }
    }

    public String requireProjectAccess(UserContext user, String requestedProjectId) {
        String effective = hasText(requestedProjectId)
                ? requestedProjectId.trim()
                : projectRegistry.defaultProject().id();
        if (hasText(effective) && (user == null || !user.hasAccessTo(effective))) {
            throw new AccessDeniedException("Insufficient permissions");
        }
        return effective;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
