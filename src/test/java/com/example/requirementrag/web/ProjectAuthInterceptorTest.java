package com.example.requirementrag.web;

import com.example.requirementrag.config.AuthProperties;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.model.UserRole;
import com.example.requirementrag.security.ApiKeyAuthenticationService;
import com.example.requirementrag.security.ProjectAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        AuthProperties auth = new AuthProperties(true, List.of(
                new AuthProperties.AuthUser("dev", "dev-key", UserRole.DEVELOPER, List.of("project-a")),
                new AuthProperties.AuthUser("viewer", "ro-key", UserRole.READONLY, List.of("project-a"))));
        interceptor = new ProjectAuthInterceptor(
                new ApiKeyAuthenticationService(auth),
                new ProjectAuthorizationService(projectRegistry),
                projectIdResolver);
    }

    @Test
    void developerCanAccessReadEndpointWithBodyProjectId() throws Exception {
        MockHttpServletRequest request = jsonPost("/api/code/search", "dev-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(projectIdResolver.resolveForAccess(request)).thenReturn("project-a");

        boolean allowed = interceptor.preHandle(request, response, handler(CodeController.class, "search"));

        assertTrue(allowed);
        assertTrue(request.getAttribute(UserContext.REQUEST_ATTRIBUTE) instanceof UserContext);
    }

    @Test
    void developerDeniedForUnauthorizedDefaultProject() throws Exception {
        MockHttpServletRequest request = jsonPost("/api/code/search", "dev-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(projectIdResolver.resolveForAccess(request)).thenReturn("project-b");

        boolean allowed = interceptor.preHandle(request, response, handler(CodeController.class, "search"));

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
    }

    @Test
    void developerDeniedForUnauthorizedProjectInBody() throws Exception {
        MockHttpServletRequest request = jsonPost("/api/code/search", "dev-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(projectIdResolver.resolveForAccess(request)).thenReturn("project-b");

        boolean allowed = interceptor.preHandle(request, response, handler(CodeController.class, "search"));

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
    }

    @Test
    void readonlyDeniedForWriteEndpoint() throws Exception {
        MockHttpServletRequest request = jsonPost("/api/code/index", "ro-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, handler(CodeController.class, "index"));

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
    }

    @Test
    void developerDeniedForWriteEndpoint() throws Exception {
        MockHttpServletRequest request = jsonPost("/api/code/index", "dev-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, handler(CodeController.class, "index"));

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
    }

    private MockHttpServletRequest jsonPost(String uri, String apiKey) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.addHeader("X-API-Key", apiKey);
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

    private static void assertEquals(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
