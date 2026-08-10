package com.example.requirementrag.web;

import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.security.ProjectAuthorizationService;
import com.example.requirementrag.security.UserContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Service 层二次校验：当前用户是否有权访问指定项目。 */
@Component
public class ProjectAccessGuard {

    private final ProjectAuthorizationService authorizationService;
    private final UserContextResolver userContextResolver;

    public ProjectAccessGuard(ProjectAuthorizationService authorizationService,
                              UserContextResolver userContextResolver) {
        this.authorizationService = authorizationService;
        this.userContextResolver = userContextResolver;
    }

    /** 获取当前请求用户；无请求属性时按认证边界解析（生产 fail-closed）。 */
    public UserContext currentUser(HttpServletRequest request) {
        UserContext user = (UserContext) request.getAttribute(UserContext.REQUEST_ATTRIBUTE);
        return user != null ? user : userContextResolver.resolve(request);
    }

    /** 校验当前用户对指定项目的访问权限，无权访问时抛 AccessDeniedException。 */
    public void requireProjectAccess(HttpServletRequest request, String projectId) {
        authorizationService.requireProjectAccess(currentUser(request), projectId);
    }
}
