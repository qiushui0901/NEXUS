package com.example.requirementrag.web;

import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.security.ProjectAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Service 层二次校验：当前用户是否有权访问指定项目。 */
@Component
public class ProjectAccessGuard {

    private final ProjectAuthorizationService authorizationService;

    public ProjectAccessGuard(ProjectAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    /** 获取当前请求用户；统一身份由外部网关管理，缺失时回退为默认管理员。 */
    public UserContext currentUser(HttpServletRequest request) {
        UserContext user = (UserContext) request.getAttribute(UserContext.REQUEST_ATTRIBUTE);
        return user != null ? user : UserContext.defaultAdmin();
    }

    /** 校验当前用户对指定项目的访问权限，无权访问时抛 AccessDeniedException。 */
    public void requireProjectAccess(HttpServletRequest request, String projectId) {
        authorizationService.requireProjectAccess(currentUser(request), projectId);
    }
}
