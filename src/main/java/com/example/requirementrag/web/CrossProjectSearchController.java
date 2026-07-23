package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.CrossProjectSearchRequest;
import com.example.requirementrag.model.CrossProjectSearchResult;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.service.CrossProjectSearchService;
import com.example.requirementrag.config.RagProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 跨项目需求检索 REST 接口：在用户有权访问的全部项目中并行搜索并合并结果。
 */
@RestController
@RequestMapping("/api/search")
public class CrossProjectSearchController {

    private final CrossProjectSearchService searchService;
    private final ProjectRegistry projectRegistry;
    private final ProjectAccessGuard accessGuard;

    public CrossProjectSearchController(CrossProjectSearchService searchService,
                                        ProjectRegistry projectRegistry,
                                        ProjectAccessGuard accessGuard) {
        this.searchService = searchService;
        this.projectRegistry = projectRegistry;
        this.accessGuard = accessGuard;
    }

    /** 在用户有权访问的全部项目中并行检索需求分块。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @PostMapping("/cross-project")
    public List<CrossProjectSearchResult> crossProjectSearch(@Valid @RequestBody CrossProjectSearchRequest request,
                                                             HttpServletRequest httpRequest) {
        UserContext user = accessGuard.currentUser(httpRequest);
        List<String> candidateProjectIds = projectRegistry.all().stream()
                .map(RagProperties.ProjectConfig::id)
                .filter(user::hasAccessTo)
                .toList();
        int topK = request.topK() == null ? 10 : request.topK();
        return searchService.fanOutSearch(request.query(), candidateProjectIds, topK);
    }
}
