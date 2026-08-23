package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 多源知识人工审核 API：确认/拒绝/标记过期关系与存疑。 */
@RestController
@RequestMapping("/api/knowledge/review")
public class KnowledgeReviewController {

    private final MultiSourceKnowledgeStore store;
    private final ProjectRegistry projectRegistry;
    private final ProjectAccessGuard accessGuard;

    public KnowledgeReviewController(MultiSourceKnowledgeStore store,
                                     ProjectRegistry projectRegistry,
                                     ProjectAccessGuard accessGuard) {
        this.store = store;
        this.projectRegistry = projectRegistry;
        this.accessGuard = accessGuard;
    }

    /** 人工审核一条统一关系。对应 POST /api/knowledge/review/relations/{relationId}。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/relations/{relationId}")
    public void reviewRelation(@PathVariable String relationId,
                               @Valid @RequestBody ReviewRequest request,
                               HttpServletRequest httpRequest) {
        projectRegistry.require(request.projectId());
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        store.reviewRelation(relationId, request.status(), "HUMAN", request.reason());
    }

    /** 关系审核请求：状态取 HUMAN_CONFIRMED / REJECTED / STALE 等。 */
    public record ReviewRequest(
            @NotBlank String projectId,
            @NotBlank String status,
            String reason
    ) {
    }
}