package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntityRecallResponse;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntitySearchResponse;
import com.example.requirementrag.knowledge.multisource.entity.EntityQueryService;
import com.example.requirementrag.knowledge.multisource.entity.EntityQueryService.EntitySearchRequest;
import com.example.requirementrag.knowledge.multisource.entity.EntityRecallService;
import com.example.requirementrag.knowledge.multisource.entity.KnowledgeAnswerService;
import com.example.requirementrag.knowledge.multisource.entity.KnowledgeAnswerService.AnswerOutcome;
import com.example.requirementrag.knowledge.multisource.entity.KnowledgeAnswerService.AnswerSection;
import com.example.requirementrag.knowledge.multisource.entity.RecallMode;
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
 * 实体中心带证据回答 API（dev md §11/§12.1）：内部先跑 entity-search 取结构化证据包，
 * 再生成可审计回答（证据引用服务端校验，LLM 不可用走确定性模板）。
 */
@RestController
@RequestMapping("/api/knowledge/entity-answer")
public class EntityAnswerController {

    private final EntityQueryService entityQueryService;
    private final EntityRecallService recallService;
    private final KnowledgeAnswerService answerService;
    private final ProjectRegistry projectRegistry;
    private final ProjectAccessGuard accessGuard;

    public EntityAnswerController(EntityQueryService entityQueryService,
                                  EntityRecallService recallService,
                                  KnowledgeAnswerService answerService,
                                  ProjectRegistry projectRegistry,
                                  ProjectAccessGuard accessGuard) {
        this.entityQueryService = entityQueryService;
        this.recallService = recallService;
        this.answerService = answerService;
        this.projectRegistry = projectRegistry;
        this.accessGuard = accessGuard;
    }

    /** 回答响应：answer + 分节引用 + 状态 + 引用质量 + 底层证据包；
     * 图/向量模式额外带完整召回包（recall：图/向量命中/相关实体数），供前端单请求渲染、避免重复召回。 */
    public record AnswerResponse(
            String query,
            String answer,
            List<AnswerSection> sections,
            String status,
            String citationQuality,
            EntitySearchResponse evidence,
            List<String> warnings,
            EntityRecallResponse recall
    ) {
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @PostMapping
    public AnswerResponse answer(@Valid @RequestBody EntitySearchController.EntitySearchRequestBody body,
                                 HttpServletRequest httpRequest) {
        projectRegistry.require(body.projectId());
        accessGuard.requireProjectAccess(httpRequest, body.projectId());
        int limit = body.limit() == null ? 20 : Math.max(1, Math.min(50, body.limit()));
        EntitySearchRequest request = new EntitySearchRequest(
                body.projectId(), body.query(), body.versions(),
                body.includeHistory(), body.includeCode(), body.includeParameters(),
                body.includeTests(), limit);
        RecallMode mode = RecallMode.DETERMINISTIC;
        if (body.recallMode() != null && !body.recallMode().isBlank()) {
            try {
                mode = RecallMode.valueOf(body.recallMode().trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                mode = RecallMode.DETERMINISTIC;
            }
        }
        if (mode != RecallMode.DETERMINISTIC) {
            EntityRecallResponse recall = recallService.search(request, mode);
            AnswerOutcome outcome = answerService.answerWithRecall(recall);
            return new AnswerResponse(body.query(), outcome.answer(), outcome.sections(),
                    outcome.status(), outcome.citationQuality(), recall.evidence(),
                    outcome.warnings(), recall);
        }
        EntitySearchResponse evidence = entityQueryService.search(request);
        AnswerOutcome outcome = answerService.answer(evidence);
        return new AnswerResponse(body.query(), outcome.answer(), outcome.sections(),
                outcome.status(), outcome.citationQuality(), evidence,
                outcome.warnings(), null);
    }
}