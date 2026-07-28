package com.example.requirementrag.mcp;

import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.security.ProjectAuthorizationService;
import com.example.requirementrag.security.UnauthenticatedException;
import com.example.requirementrag.wiki.WikiModels;
import com.example.requirementrag.wiki.WikiRepository;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Authenticated resource-template facade for published Wiki feature pages. */
@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NexusMcpResources {
    private final WikiRepository wikiRepository;
    private final ProjectAuthorizationService authorizationService;
    private final McpResponsePolicy policy;
    private final JsonMapper jsonMapper;

    public NexusMcpResources(WikiRepository wikiRepository, ProjectAuthorizationService authorizationService,
                             McpResponsePolicy policy, JsonMapper jsonMapper) {
        this.wikiRepository = wikiRepository;
        this.authorizationService = authorizationService;
        this.policy = policy;
        this.jsonMapper = jsonMapper;
    }

    @McpResource(
            name = "nexus_wiki_feature",
            title = "NEXUS Wiki feature page",
            uri = "nexus://wiki/{projectId}/{version}/{featureId}",
            description = "Published, version-scoped feature knowledge with bounded evidence metadata.",
            mimeType = "application/json")
    public String wikiFeature(McpSyncRequestContext context, String projectId, String version, String featureId) {
        UserContext user = authenticatedUser(context);
        authorizationService.requirePermission(user, Permission.PUBLIC_READ);
        String effectiveProject = authorizationService.requireProjectAccess(user, projectId);
        WikiModels.Page page = wikiRepository.getPage(effectiveProject, version, featureId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", effectiveProject);
        result.put("version", page.version());
        result.put("featureId", page.featureId());
        result.put("title", policy.bounded(page.title()));
        result.put("status", page.status());
        result.put("summary", policy.bounded(page.summary()));
        result.put("productRules", page.productRules().stream().limit(20).map(policy::bounded).toList());
        result.put("processSteps", page.processSteps().stream().limit(20).map(policy::bounded).toList());
        result.put("acceptanceCriteria",
                page.acceptanceCriteria().stream().limit(20).map(policy::bounded).toList());
        result.put("evidence", page.evidence().stream().limit(40).map(policy::wikiEvidence).toList());
        try {
            return jsonMapper.writeValueAsString(result);
        }
        catch (RuntimeException exception) {
            throw new IllegalStateException("Wiki resource serialization failed");
        }
    }

    private UserContext authenticatedUser(McpSyncRequestContext context) {
        if (context != null && context.transportContext() != null) {
            Object value = context.transportContext().get(McpTransportConfiguration.USER_CONTEXT_KEY);
            if (value instanceof UserContext user) return user;
        }
        throw new UnauthenticatedException();
    }
}
