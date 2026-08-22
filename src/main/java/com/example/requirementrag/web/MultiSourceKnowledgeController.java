package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.MultiSourceSearchRequest;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.MultiSourceSearchResponse;
import com.example.requirementrag.knowledge.multisource.MultiSourceSearchService;
import com.example.requirementrag.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 多源需求知识统一检索 HTTP API。对应 POST /api/knowledge/multi-source/search。 */
@RestController
@RequestMapping("/api/knowledge/multi-source")
public class MultiSourceKnowledgeController {

    private final MultiSourceSearchService searchService;
    private final ProjectRegistry projectRegistry;
    private final ProjectAccessGuard accessGuard;

    public MultiSourceKnowledgeController(MultiSourceSearchService searchService,
                                          ProjectRegistry projectRegistry,
                                          ProjectAccessGuard accessGuard) {
        this.searchService = searchService;
        this.projectRegistry = projectRegistry;
        this.accessGuard = accessGuard;
    }

    /** 按项目/版本执行多源检索，意图、分页可省略。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @PostMapping("/search")
    public MultiSourceSearchResponse search(@Valid @RequestBody MultiSourceSearchRequest request,
                                            HttpServletRequest httpRequest) {
        projectRegistry.require(request.projectId());
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return searchService.search(request.projectId(), request.version(), request.query(),
                request.intent(),
                request.limit() == null ? 20 : request.limit(),
                request.page() == null ? 0 : request.page());
    }
}