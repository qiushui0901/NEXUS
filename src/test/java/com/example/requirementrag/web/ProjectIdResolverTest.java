package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectIdResolverTest {

    @Mock
    private ProjectRegistry projectRegistry;

    private ProjectIdResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ProjectIdResolver(new ObjectMapper(), projectRegistry);
    }

    @Test
    void resolvesProjectIdFromQueryParam() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/code/search");
        request.setParameter("projectId", "fengshen-server");
        assertEquals("fengshen-server", resolver.resolve(request));
    }

    @Test
    void resolvesProjectIdFromJsonBody() throws Exception {
        byte[] body = "{\"query\":\"test\",\"projectId\":\"mengchong-server\"}".getBytes();
        CachedBodyHttpServletRequest request = new CachedBodyHttpServletRequest(wrapWithBody(body));
        assertEquals("mengchong-server", resolver.resolve(request));
    }

    @Test
    void returnsNullWhenProjectIdAbsent() throws Exception {
        byte[] body = "{\"query\":\"test\"}".getBytes();
        CachedBodyHttpServletRequest request = new CachedBodyHttpServletRequest(wrapWithBody(body));
        assertNull(resolver.resolve(request));
    }

    @Test
    void resolveForAccessFallsBackToDefaultProject() throws Exception {
        RagProperties.ProjectConfig defaultProject = new RagProperties.ProjectConfig(
                "fengshen-server", "封神", "fengshen", "server",
                "req", "code", "/repo", null, null, List.of(), List.of(), 1_000_000);
        when(projectRegistry.defaultProject()).thenReturn(defaultProject);

        byte[] body = "{\"query\":\"test\"}".getBytes();
        CachedBodyHttpServletRequest request = new CachedBodyHttpServletRequest(wrapWithBody(body));
        assertEquals("fengshen-server", resolver.resolveForAccess(request));
    }

    private MockHttpServletRequest wrapWithBody(byte[] body) {
        MockHttpServletRequest base = new MockHttpServletRequest("POST", "/api/code/search");
        base.setContentType("application/json");
        base.setContent(body);
        return base;
    }
}
