package com.example.requirementrag.web;

import com.example.requirementrag.config.AuthProperties;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
public class ProjectAuthInterceptor implements HandlerInterceptor {

    private static final String API_KEY_HEADER = "X-API-Key";

    private final AuthProperties authProperties;
    private final ProjectIdResolver projectIdResolver;

    public ProjectAuthInterceptor(AuthProperties authProperties, ProjectIdResolver projectIdResolver) {
        this.authProperties = authProperties;
        this.projectIdResolver = projectIdResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!authProperties.enabled()) {
            request.setAttribute(UserContext.REQUEST_ATTRIBUTE, UserContext.defaultAdmin());
            return true;
        }

        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid API key");
            return false;
        }

        UserContext user = resolveUser(apiKey.trim());
        if (user == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid API key");
            return false;
        }

        Permission required = resolveRequiredPermission(request, handler);
        if (!user.hasPermission(required)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Insufficient permissions");
            return false;
        }

        String projectId = projectIdResolver.resolveForAccess(request);
        if (!user.hasAccessTo(projectId)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Insufficient permissions");
            return false;
        }

        request.setAttribute(UserContext.REQUEST_ATTRIBUTE, user);
        return true;
    }

    private UserContext resolveUser(String apiKey) {
        for (AuthProperties.AuthUser user : authProperties.users()) {
            if (constantTimeEquals(apiKey, user.apiKey())) {
                return new UserContext(user.username(), user.role(), List.copyOf(user.projects()));
            }
        }
        return null;
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
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
