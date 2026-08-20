package com.example.requirementrag.web;

import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.security.ProjectAuthorizationService;
import com.example.requirementrag.project.BusinessProjectCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectAuthInterceptorTest {

    @Mock
    private ProjectIdResolver projectIdResolver;
    @Mock
    private com.example.requirementrag.config.ProjectRegistry projectRegistry;

    private ProjectAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new ProjectAuthInterceptor(
                new ProjectAuthorizationService(projectRegistry),
                projectIdResolver,
                new com.example.requirementrag.security.UserContextResolver(
                        new com.example.requirementrag.config.AuthProperties(null, null, null, true)));
    }

    @Test
    void rejectsRequestsWhenDefaultAdminIsForbidden() throws Exception {
        interceptor = new ProjectAuthInterceptor(
                new ProjectAuthorizationService(projectRegistry),
                projectIdResolver,
                new com.example.requirementrag.security.UserContextResolver(
                        new com.example.requirementrag.config.AuthProperties(null, null, null, false)));
        MockHttpServletRequest request = jsonPost("/api/code/search");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, handler(CodeController.class, "search"));

        assertFalse(allowed);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    @Test
    void acceptsTrustedGatewayIdentityHeader() throws Exception {
        interceptor = new ProjectAuthInterceptor(
                new ProjectAuthorizationService(projectRegistry),
                projectIdResolver,
                new com.example.requirementrag.security.UserContextResolver(
                        new com.example.requirementrag.config.AuthProperties("X-Gateway-User", null, null, false)));
        MockHttpServletRequest request = jsonPost("/api/code/search");
        request.addHeader("X-Gateway-User", "gateway-bot");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(projectIdResolver.resolveForAccess(request)).thenReturn("any-project");

        boolean allowed = interceptor.preHandle(request, response, handler(CodeController.class, "search"));

        assertTrue(allowed);
        assertThat(request.getAttribute(UserContext.REQUEST_ATTRIBUTE))
                .isInstanceOfSatisfying(UserContext.class, user -> {
                    assertThat(user.username()).isEqualTo("gateway-bot");
                    assertThat(user.role()).as("身份头只断言身份，不授予提升权限")
                            .isEqualTo(com.example.requirementrag.model.UserRole.READONLY);
                });
    }

    @Test
    void identityHeaderWithoutRoleCannotExecuteWriteOperations() throws Exception {
        interceptor = new ProjectAuthInterceptor(
                new ProjectAuthorizationService(projectRegistry),
                projectIdResolver,
                new com.example.requirementrag.security.UserContextResolver(
                        new com.example.requirementrag.config.AuthProperties("X-Gateway-User", null, null, false)));
        MockHttpServletRequest request = jsonPost("/api/code/index");
        request.addHeader("X-Gateway-User", "forged");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, handler(CodeController.class, "index"));

        assertFalse(allowed);
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
    }

    @Test
    void invalidRoleHeaderIsRejected() throws Exception {
        interceptor = new ProjectAuthInterceptor(
                new ProjectAuthorizationService(projectRegistry),
                projectIdResolver,
                new com.example.requirementrag.security.UserContextResolver(
                        new com.example.requirementrag.config.AuthProperties("X-Gateway-User",
                                "X-Gateway-Role", null, false)));
        MockHttpServletRequest request = jsonPost("/api/code/search");
        request.addHeader("X-Gateway-User", "gateway-bot");
        request.addHeader("X-Gateway-Role", "GOD_MODE");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, handler(CodeController.class, "search"));

        assertFalse(allowed);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    @Test
    void identityHeaderFromUntrustedSourceIsRejected() throws Exception {
        interceptor = new ProjectAuthInterceptor(
                new ProjectAuthorizationService(projectRegistry),
                projectIdResolver,
                new com.example.requirementrag.security.UserContextResolver(
                        new com.example.requirementrag.config.AuthProperties("X-Gateway-User",
                                null, "10.0.0.0/8", false)));
        MockHttpServletRequest request = jsonPost("/api/code/search");
        request.addHeader("X-Gateway-User", "gateway-bot");
        request.setRemoteAddr("192.168.1.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, handler(CodeController.class, "search"));

        assertFalse(allowed);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    @Test
    void defaultAdminAllowedForAnyProject() throws Exception {
        MockHttpServletRequest request = jsonPost("/api/code/search");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(projectIdResolver.resolveForAccess(request)).thenReturn("any-project");

        boolean allowed = interceptor.preHandle(request, response, handler(CodeController.class, "search"));

        assertTrue(allowed);
        assertTrue(request.getAttribute(UserContext.REQUEST_ATTRIBUTE) instanceof UserContext);
    }

    @Test
    void writeEndpointAllowedForDefaultAdmin() throws Exception {
        MockHttpServletRequest request = jsonPost("/api/code/index");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(projectIdResolver.resolveForAccess(request)).thenReturn("project-a");

        boolean allowed = interceptor.preHandle(request, response, handler(CodeController.class, "index"));

        assertTrue(allowed);
    }

    @Test
    void projectAccessAlwaysGrantedForDefaultAdmin() throws Exception {
        MockHttpServletRequest request = jsonPost("/api/code/search");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(projectIdResolver.resolveForAccess(request)).thenReturn("unlisted-project");

        boolean allowed = interceptor.preHandle(request, response, handler(CodeController.class, "search"));

        assertTrue(allowed);
        assertFalse(response.getStatus() == 403);
    }

    @Test
    void acceptsBusinessProjectWhenUserOnlyHasTheLegacyAlias() throws Exception {
        BusinessProjectCatalogService catalog = org.mockito.Mockito.mock(BusinessProjectCatalogService.class);
        com.example.requirementrag.security.UserContextResolver users =
                org.mockito.Mockito.mock(com.example.requirementrag.security.UserContextResolver.class);
        interceptor = new ProjectAuthInterceptor(new ProjectAuthorizationService(projectRegistry),
                projectIdResolver, users, catalog);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/business-projects/immortal");
        MockHttpServletResponse response = new MockHttpServletResponse();
        UserContext user = new UserContext("reader", com.example.requirementrag.model.UserRole.READONLY,
                java.util.List.of("immortal-game-service"));
        when(users.resolve(request)).thenReturn(user);
        when(projectIdResolver.resolveForAccess(request)).thenReturn("immortal");
        when(catalog.accessScopeIds("immortal"))
                .thenReturn(java.util.List.of("immortal", "immortal-game-service"));

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
    }

    private MockHttpServletRequest jsonPost(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContentType("application/json");
        return request;
    }

    private HandlerMethod handler(Class<?> controller, String methodName) throws NoSuchMethodException {
        Method method = switch (methodName) {
            case "search" -> controller.getMethod("search",
                    com.example.requirementrag.model.CodeSearchRequest.class,
                    jakarta.servlet.http.HttpServletRequest.class);
            case "index" -> controller.getMethod("index", String.class, jakarta.servlet.http.HttpServletRequest.class);
            default -> throw new IllegalArgumentException(methodName);
        };
        return new HandlerMethod(new Object(), method);
    }
}
