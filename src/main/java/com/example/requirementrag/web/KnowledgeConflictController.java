package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.conflict.KnowledgeConflictModels.AnalyzeRequest;
import com.example.requirementrag.conflict.KnowledgeConflictModels.KnowledgeConflictReport;
import com.example.requirementrag.conflict.KnowledgeConflictService;
import com.example.requirementrag.model.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Analyzes structured claims without mutating or automatically arbitrating source evidence. */
@RestController
@RequestMapping("/api/knowledge/conflicts")
public class KnowledgeConflictController {
    private final KnowledgeConflictService conflictService;
    private final ProjectRegistry projectRegistry;
    private final ProjectAccessGuard accessGuard;

    public KnowledgeConflictController(KnowledgeConflictService conflictService, ProjectRegistry projectRegistry,
                                       ProjectAccessGuard accessGuard) {
        this.conflictService = conflictService;
        this.projectRegistry = projectRegistry;
        this.accessGuard = accessGuard;
    }

    @RequiresPermission(Permission.OPERATE)
    @PostMapping("/analyze")
    public KnowledgeConflictReport analyze(@Valid @RequestBody AnalyzeRequest request,
                                           HttpServletRequest httpRequest) {
        projectRegistry.require(request.projectId());
        accessGuard.requireProjectAccess(httpRequest, request.projectId());
        return conflictService.analyze(request);
    }
}
