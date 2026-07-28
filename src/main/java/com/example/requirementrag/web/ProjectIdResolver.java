package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 从 query 参数或 JSON body 解析 projectId。 */
@Component
public class ProjectIdResolver {
    private static final Logger log = LoggerFactory.getLogger(ProjectIdResolver.class);

    private final ObjectMapper objectMapper;
    private final ProjectRegistry projectRegistry;

    public ProjectIdResolver(ObjectMapper objectMapper, ProjectRegistry projectRegistry) {
        this.objectMapper = objectMapper;
        this.projectRegistry = projectRegistry;
    }

    public String resolve(HttpServletRequest request) {
        String fromQuery = request.getParameter("projectId");
        if (hasText(fromQuery)) {
            return fromQuery.trim();
        }
        if (request instanceof CachedBodyHttpServletRequest cached) {
            return extractFromJson(cached.getCachedBody());
        }
        return null;
    }

    /** 用于鉴权：显式 projectId 优先，否则回退到默认项目。 */
    public String resolveForAccess(HttpServletRequest request) {
        String explicit = resolve(request);
        if (hasText(explicit)) {
            return explicit;
        }
        String defaultId = projectRegistry.defaultProject().id();
        return hasText(defaultId) ? defaultId : null;
    }

    private String extractFromJson(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root != null && root.hasNonNull("projectId")) {
                String projectId = root.get("projectId").asText();
                return hasText(projectId) ? projectId.trim() : null;
            }
        }
        catch (RuntimeException exception) {
            log.warn("Unable to parse projectId from the cached JSON request body; request content is omitted",
                    exception);
            return null;
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
