package com.example.requirementrag.web;

import com.example.requirementrag.model.DevelopmentPlanRequest;
import com.example.requirementrag.model.DevelopmentPlanResponse;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.service.DevelopmentPlanService;
import com.example.requirementrag.service.DevelopmentPlanStreamService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 面向开发者的代码理解与入手建议接口。
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final DevelopmentPlanService developmentPlanService;
    private final DevelopmentPlanStreamService developmentPlanStreamService;
    private final ProjectAccessGuard accessGuard;

    /** 注入开发方案服务。 */
    public AssistantController(DevelopmentPlanService developmentPlanService,
                               DevelopmentPlanStreamService developmentPlanStreamService,
                               ProjectAccessGuard accessGuard) {
        this.developmentPlanService = developmentPlanService;
        this.developmentPlanStreamService = developmentPlanStreamService;
        this.accessGuard = accessGuard;
    }

    /** 结合需求文档向量与代码向量，生成开发入手方案。 */
    @RequiresPermission(Permission.OPERATE)
    @PostMapping("/development-plan")
    public DevelopmentPlanResponse developmentPlan(@Valid @RequestBody DevelopmentPlanRequest request,
                                                   HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return developmentPlanService.plan(request.query(), request.documentId(), request.version(),
                request.projectId(), request.limit());
    }

    /** 通过 SSE 逐段返回真实生成中的开发方案。 */
    @RequiresPermission(Permission.OPERATE)
    @PostMapping(value = "/development-plan/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter developmentPlanStream(@Valid @RequestBody DevelopmentPlanRequest request,
                                            HttpServletRequest httpRequest) {
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return developmentPlanStreamService.stream(request);
    }
}
