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

/**
 * 已发布 Wiki 特性页的资源模板门面：对 MCP 客户端暴露受认证、按版本作用域的
 * Wiki 资源（{@code nexus://wiki/{projectId}/{version}/{featureId}}），
 * 输出前经 {@link McpResponsePolicy} 做证据元数据边界约束，并以 JSON 序列化返回。
 */
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

    /**
     * 读取指定项目/版本下的 Wiki 特性页：认证 → 权限校验 → 项目解析 → 读取页面，
     * 各文本字段经 {@code policy} 截断（规则/步骤/验收条件各限 20 条、证据限 40 条），
     * 序列化为 JSON 字符串返回。
     *
     * @param context   MCP 同步请求上下文（取认证用户）
     * @param projectId 项目 ID，可为 null（走默认项目）
     * @param version   发布的 Wiki 版本
     * @param featureId 稳定的 Wiki 特性 ID
     * @return 序列化后的页面 JSON
     * @throws com.example.requirementrag.security.UnauthenticatedException 未认证时抛出
     * @throws IllegalStateException 序列化失败时抛出
     */
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

    /** 从传输上下文取回认证用户；上下文缺失或类型不符时按未认证处理。 */
    private UserContext authenticatedUser(McpSyncRequestContext context) {
        if (context != null && context.transportContext() != null) {
            Object value = context.transportContext().get(McpTransportConfiguration.USER_CONTEXT_KEY);
            if (value instanceof UserContext user) return user;
        }
        throw new UnauthenticatedException();
    }
}
