package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntitySearchResponse;
import com.example.requirementrag.knowledge.multisource.entity.EntityGraphExpansionService;
import com.example.requirementrag.knowledge.multisource.entity.EntityGraphExpansionService.EntityRetrievalMetrics;
import com.example.requirementrag.knowledge.multisource.entity.EntityGraphExpansionService.RelatedGraph;
import com.example.requirementrag.knowledge.multisource.entity.EntityQueryService;
import com.example.requirementrag.knowledge.multisource.entity.EntityQueryService.EntitySearchRequest;
import com.example.requirementrag.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 实体中心证据查询 API（dev md §8.1）：问题 → 实体提取/解析 → 全版本聚合 →
 * 结构化证据包（currentFacts + timeline + relations + conflicts + warnings）。
 * 第一版不接入现有多源检索，行为互不影响。
 */
@RestController
@RequestMapping("/api/knowledge/entity-search")
public class EntitySearchController {

    private final EntityQueryService entityQueryService;
    private final EntityGraphExpansionService graphExpansionService;
    private final ProjectRegistry projectRegistry;
    private final ProjectAccessGuard accessGuard;

    public EntitySearchController(EntityQueryService entityQueryService,
                                  EntityGraphExpansionService graphExpansionService,
                                  ProjectRegistry projectRegistry,
                                  ProjectAccessGuard accessGuard) {
        this.entityQueryService = entityQueryService;
        this.graphExpansionService = graphExpansionService;
        this.projectRegistry = projectRegistry;
        this.accessGuard = accessGuard;
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @PostMapping("/related")
    public RelatedAndMetrics related(@Valid @RequestBody EntitySearchRequestBody body,
                                     HttpServletRequest httpRequest) {
        projectRegistry.require(body.projectId());
        accessGuard.requireProjectAccess(httpRequest, body.projectId());
        EntitySearchRequest request = new EntitySearchRequest(
                body.projectId(), body.query(), body.versions(),
                body.includeHistory(), body.includeCode(), body.includeParameters(),
                body.includeTests(), body.limit() == null ? 20 : body.limit());
        EntitySearchResponse response = entityQueryService.search(request);
        RelatedGraph related = graphExpansionService.expand(body.projectId(), response);
        EntityRetrievalMetrics metrics = graphExpansionService.metrics(response);
        return new RelatedAndMetrics(related, metrics);
    }

    /** 局部图扩展 + 检索指标响应。 */
    public record RelatedAndMetrics(RelatedGraph related, EntityRetrievalMetrics metrics) {
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @PostMapping
    public EntitySearchResponse search(@Valid @RequestBody EntitySearchRequestBody body,
                                       HttpServletRequest httpRequest) {
        projectRegistry.require(body.projectId());
        accessGuard.requireProjectAccess(httpRequest, body.projectId());
        int limit = body.limit() == null ? 20 : Math.max(1, Math.min(50, body.limit()));
        EntitySearchRequest request = new EntitySearchRequest(
                body.projectId(), body.query(), body.versions(),
                body.includeHistory(), body.includeCode(), body.includeParameters(),
                body.includeTests(), limit);
        return entityQueryService.search(request);
    }

    /** 请求体（projectId + query 必填；versions 为空 = 全部相关版本）。 */
    public record EntitySearchRequestBody(
            @NotBlank String projectId,
            @NotBlank String query,
            List<String> versions,
            Boolean includeHistory,
            Boolean includeCode,
            Boolean includeParameters,
            Boolean includeTests,
            Integer limit
    ) {
        // 参数合法性由服务层聚合上限兜底，此处只做必填与结构校验
        public EntitySearchRequestBody {
            versions = versions == null ? List.of() : versions;
        }
    }
}