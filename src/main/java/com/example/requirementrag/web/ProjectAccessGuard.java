package com.example.requirementrag.web;

import com.example.requirementrag.config.AuthProperties;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Service 层二次校验：当前用户是否有权访问指定项目。 */
@Component
public class ProjectAccessGuard {

    private final AuthProperties authProperties;
    private final ProjectRegistry projectRegistry;

    public ProjectAccessGuard(AuthProperties authProperties, ProjectRegistry projectRegistry) {
        this.authProperties = authProperties;
        this.projectRegistry = projectRegistry;
    }

    public UserContext currentUser(HttpServletRequest request) {
        UserContext user = (UserContext) request.getAttribute(UserContext.REQUEST_ATTRIBUTE);
        if (user != null) {
            return user;
        }
        if (!authProperties.enabled()) {
            return UserContext.defaultAdmin();
        }
        throw new AccessDeniedException("未认证");
    }

    public void requireProjectAccess(HttpServletRequest request, String projectId) {
        String effective = hasText(projectId) ? projectId.trim() : projectRegistry.defaultProject().id();
        if (!hasText(effective)) {
            return;
        }
        UserContext user = currentUser(request);
        if (!user.hasAccessTo(effective)) {
            throw new AccessDeniedException("无权访问该项目");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
