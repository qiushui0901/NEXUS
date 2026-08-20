package com.example.requirementrag.web;

import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.security.ProjectAuthorizationService;
import com.example.requirementrag.security.UnauthenticatedException;
import com.example.requirementrag.security.UserContextResolver;
import com.example.requirementrag.project.BusinessProjectCatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** 请求鉴权拦截器：统一身份由外部网关管理，内部以默认管理员上下文放行，保留权限与项目访问校验框架。 */
@Component
public class ProjectAuthInterceptor implements HandlerInterceptor {

    private final ProjectAuthorizationService authorizationService;
    private final ProjectIdResolver projectIdResolver;
    private final UserContextResolver userContextResolver;
    private final BusinessProjectCatalogService businessProjects;

    @Autowired
    public ProjectAuthInterceptor(ProjectAuthorizationService authorizationService,
                                  ProjectIdResolver projectIdResolver,
                                  UserContextResolver userContextResolver,
                                  BusinessProjectCatalogService businessProjects) {
        this.authorizationService = authorizationService;
        this.projectIdResolver = projectIdResolver;
        this.userContextResolver = userContextResolver;
        this.businessProjects = businessProjects;
    }

    public ProjectAuthInterceptor(ProjectAuthorizationService authorizationService,
                                  ProjectIdResolver projectIdResolver,
                                  UserContextResolver userContextResolver) {
        this(authorizationService, projectIdResolver, userContextResolver, null);
    }

    /** 解析可信身份后执行权限与项目访问校验；无法建立身份时返回 401，通过后将 UserContext 写入请求属性。 */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        UserContext user;
        try {
            user = userContextResolver.resolve(request);
        } catch (UnauthenticatedException exception) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, exception.getMessage());
            return false;
        }

        Permission required = resolveRequiredPermission(request, handler);
        try {
            authorizationService.requirePermission(user, required);
            String requestedProjectId = projectIdResolver.resolveForAccess(request);
            if (requestedProjectId != null && !hasAccess(user, requestedProjectId)) {
                throw new AccessDeniedException("Insufficient permissions");
            }
        }
        catch (AccessDeniedException exception) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Insufficient permissions");
            return false;
        }

        request.setAttribute(UserContext.REQUEST_ATTRIBUTE, user);
        return true;
    }

    private boolean hasAccess(UserContext user, String requestedProjectId) {
        if (businessProjects == null) {
            authorizationService.requireProjectAccess(user, requestedProjectId);
            return true;
        }
        return businessProjects.accessScopeIds(requestedProjectId).stream().anyMatch(user::hasAccessTo);
    }

    /** 解析方法或类上的 @RequiresPermission，均未标注时按 HTTP 方法取默认权限。 */
    private Permission resolveRequiredPermission(HttpServletRequest request, Object handler) {
        if (handler instanceof HandlerMethod handlerMethod) {
            RequiresPermission methodAnnotation = handlerMethod.getMethodAnnotation(RequiresPermission.class);
            if (methodAnnotation != null) {
                return methodAnnotation.value();
            }
            RequiresPermission typeAnnotation = handlerMethod.getBeanType().getAnnotation(RequiresPermission.class);
            if (typeAnnotation != null) {
                return typeAnnotation.value();
            }
        }
        return defaultPermission(request);
    }

    /** GET/HEAD 请求默认 PUBLIC_READ，其余默认 WRITE。 */
    private Permission defaultPermission(HttpServletRequest request) {
        String method = request.getMethod();
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)) {
            return Permission.PUBLIC_READ;
        }
        return Permission.WRITE;
    }
}
