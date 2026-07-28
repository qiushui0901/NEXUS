package com.example.requirementrag.web;

import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.security.ApiKeyAuthenticationService;
import com.example.requirementrag.security.ProjectAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Service 层二次校验：当前用户是否有权访问指定项目。 */
@Component
public class ProjectAccessGuard {

    private final ApiKeyAuthenticationService authenticationService;
    private final ProjectAuthorizationService authorizationService;

    public ProjectAccessGuard(ApiKeyAuthenticationService authenticationService,
                              ProjectAuthorizationService authorizationService) {
        this.authenticationService = authenticationService;
        this.authorizationService = authorizationService;
    }

    public UserContext currentUser(HttpServletRequest request) {
        UserContext user = (UserContext) request.getAttribute(UserContext.REQUEST_ATTRIBUTE);
        if (user != null) {
            return user;
        }
        if (!authenticationService.enabled()) {
            return UserContext.defaultAdmin();
        }
        throw new AccessDeniedException("未认证");
    }

    public void requireProjectAccess(HttpServletRequest request, String projectId) {
        authorizationService.requireProjectAccess(currentUser(request), projectId);
    }
}
