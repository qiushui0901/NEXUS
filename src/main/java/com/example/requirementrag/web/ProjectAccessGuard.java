package com.example.requirementrag.web;

import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.project.BusinessProjectCatalogService;
import com.example.requirementrag.security.ProjectAuthorizationService;
import com.example.requirementrag.security.UserContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Service 层二次校验：当前用户是否有权访问指定项目/仓库（继承业务项目权限）。 */
@Component
public class ProjectAccessGuard {

    private final ProjectAuthorizationService authorizationService;
    private final UserContextResolver userContextResolver;
    private final BusinessProjectCatalogService catalogService;

    public ProjectAccessGuard(ProjectAuthorizationService authorizationService,
                              UserContextResolver userContextResolver,
                              BusinessProjectCatalogService catalogService) {
        this.authorizationService = authorizationService;
        this.userContextResolver = userContextResolver;
        this.catalogService = catalogService;
    }

    /** 获取当前请求用户；无请求属性时按认证边界解析（生产 fail-closed）。 */
    public UserContext currentUser(HttpServletRequest request) {
        UserContext user = (UserContext) request.getAttribute(UserContext.REQUEST_ATTRIBUTE);
        return user != null ? user : userContextResolver.resolve(request);
    }

    /** 校验当前用户对指定项目/仓库的访问权限（业务项目权限继承到自有仓库与旧别名）。 */
    public void requireProjectAccess(HttpServletRequest request, String projectId) {
        UserContext user = currentUser(request);
        for (String scopeId : catalogService.accessScopeIds(projectId)) {
            try {
                authorizationService.requireProjectAccess(user, scopeId);
                return;
            } catch (AccessDeniedException ignored) {
                // try next scope
            }
        }
        // fallback to direct check to preserve original error message
        authorizationService.requireProjectAccess(user, projectId);
    }
}
