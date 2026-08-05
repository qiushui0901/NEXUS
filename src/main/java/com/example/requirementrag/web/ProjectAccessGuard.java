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

    /** 获取当前请求用户；认证服务关闭时回退为默认管理员，未认证时抛异常。 */
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

    /** 校验当前用户对指定项目的访问权限，无权访问时抛 AccessDeniedException。 */
    public void requireProjectAccess(HttpServletRequest request, String projectId) {
        authorizationService.requireProjectAccess(currentUser(request), projectId);
    }
}
