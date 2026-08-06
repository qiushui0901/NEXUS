package com.example.requirementrag.mcp;

import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.security.ProjectAuthorizationService;
import com.example.requirementrag.wiki.WikiModels;
import com.example.requirementrag.wiki.WikiRepository;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已发布 Wiki 特性页的资源模板：对 MCP 客户端暴露受认证、按版本作用域的
 * Wiki 资源（{@code nexus://wiki/{projectId}/{version}/{featureId}}），
 * 输出前经 {@link McpResponsePolicy} 做证据元数据边界约束，并以 JSON 序列化返回。
 * 用户上下文经 {@link McpUserContextHolder} 从请求线程取回。
 */
@Configuration
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

    /** 注册 Wiki 特性页资源模板（nexus://wiki/{projectId}/{version}/{featureId}）。 */
    @Bean
    List<McpServerFeatures.SyncResourceTemplateSpecification> nexusWikiFeatureResourceTemplate() {
        McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder()
                .uriTemplate("nexus://wiki/{projectId}/{version}/{featureId}")
                .name("nexus_wiki_feature")
                .title("NEXUS Wiki feature page")
                .description("Published, version-scoped feature knowledge with bounded evidence metadata.")
                .mimeType("application/json")
                .build();
        return List.of(new McpServerFeatures.SyncResourceTemplateSpecification(template, this::readWikiFeature));
    }

    /**
     * 读取指定项目/版本下的 Wiki 特性页：认证 → 权限校验 → 项目解析 → 读取页面，
     * 各文本字段经 {@code policy} 截断（规则/步骤/验收条件各限 20 条、证据限 40 条），
     * 序列化为 JSON 字符串返回。
     *
     * @param exchange MCP 同步交换（保留以兼容资源处理器签名）
     * @param request  资源读取请求（含模板变量）
     * @return 序列化后的页面 JSON
     */
    McpSchema.ReadResourceResult readWikiFeature(McpSyncServerExchange exchange,
                                                 McpSchema.ReadResourceRequest request) {
        String[] segments = request.uri().split("/");
        String projectId = segments.length > 3 ? segments[3] : null;
        String version = segments.length > 4 ? segments[4] : null;
        String featureId = segments.length > 5 ? segments[5] : null;
        UserContext user = McpUserContextHolder.get();
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
        String uri = "nexus://wiki/" + effectiveProject + "/" + version + "/" + featureId;
        try {
            String text = jsonMapper.writeValueAsString(result);
            return new McpSchema.ReadResourceResult(List.of(
                    new McpSchema.TextResourceContents(uri, "application/json", text)));
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException exception) {
            throw new IllegalStateException("Wiki resource serialization failed");
        }
    }
}
