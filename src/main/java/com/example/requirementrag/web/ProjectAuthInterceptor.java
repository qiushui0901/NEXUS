package com.example.requirementrag.web;

import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.security.ApiKeyAuthenticationService;
import com.example.requirementrag.security.ProjectAuthorizationService;
import com.example.requirementrag.security.UnauthenticatedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ProjectAuthInterceptor implements HandlerInterceptor {

    private final ApiKeyAuthenticationService authenticationService;
    private final ProjectAuthorizationService authorizationService;
    private final ProjectIdResolver projectIdResolver;

    public ProjectAuthInterceptor(ApiKeyAuthenticationService authenticationService,
                                  ProjectAuthorizationService authorizationService,
                                  ProjectIdResolver projectIdResolver) {
        this.authenticationService = authenticationService;
        this.authorizationService = authorizationService;
        this.projectIdResolver = projectIdResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        UserContext user;
        try {
            user = authenticationService.authenticate(request.getHeader(ApiKeyAuthenticationService.API_KEY_HEADER));
        }
        catch (UnauthenticatedException exception) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid API key");
            return false;
        }

        Permission required = resolveRequiredPermission(request, handler);
        try {
            authorizationService.requirePermission(user, required);
            authorizationService.requireProjectAccess(user, projectIdResolver.resolveForAccess(request));
        }
        catch (AccessDeniedException exception) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Insufficient permissions");
            return false;
        }

        request.setAttribute(UserContext.REQUEST_ATTRIBUTE, user);
        return true;
    }

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

    private Permission defaultPermission(HttpServletRequest request) {
        String method = request.getMethod();
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)) {
            return Permission.PUBLIC_READ;
        }
        return Permission.WRITE;
    }
}
