package com.example.requirementrag.web;

import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.security.ProjectAuthorizationService;
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
                        new com.example.requirementrag.config.AuthProperties(null, true)));
    }

    @Test
    void rejectsRequestsWhenDefaultAdminIsForbidden() throws Exception {
        interceptor = new ProjectAuthInterceptor(
                new ProjectAuthorizationService(projectRegistry),
                projectIdResolver,
                new com.example.requirementrag.security.UserContextResolver(
                        new com.example.requirementrag.config.AuthProperties(null, false)));
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
                        new com.example.requirementrag.config.AuthProperties("X-Gateway-User", false)));
        MockHttpServletRequest request = jsonPost("/api/code/search");
        request.addHeader("X-Gateway-User", "gateway-bot");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(projectIdResolver.resolveForAccess(request)).thenReturn("any-project");

        boolean allowed = interceptor.preHandle(request, response, handler(CodeController.class, "search"));

        assertTrue(allowed);
        assertThat(request.getAttribute(UserContext.REQUEST_ATTRIBUTE))
                .isInstanceOfSatisfying(UserContext.class, user ->
                        assertThat(user.username()).isEqualTo("gateway-bot"));
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
