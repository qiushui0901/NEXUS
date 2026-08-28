package com.example.requirementrag.web;

import com.example.requirementrag.knowledge.multisource.vector.ClaimVectorQualityGate;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorBuildService;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.ClaimVectorGenerationManifest;
import com.example.requirementrag.model.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Claim 向量投影运维管理 API（高：Review 10——运维手册列出的 build/status/quality-gate/rollback 端点此前不存在，
 * 且手册引用了不存在的 rollbackTo；此处补齐，使手册描述的构建、检查与指定代际回滚均可执行）。
 * <p>仅当 {@code app.rag.multi-source.claim-vector.enabled=true} 且
 * {@code build-enabled=true} 时装配——与本 Controller 的强依赖
 * KnowledgeClaimVectorBuildService（同样要求 enabled+build-enabled）条件对齐；
 * 高（Review 5）：候选/影子阶段 enabled=true+build-enabled=false 时不装配本 Controller，
 * 避免因 BuildService 条件 Bean 不存在导致应用启动失败。</p>
 */
@RestController
@RequestMapping("/api/knowledge/multi-source/claim-vector")
@ConditionalOnProperty(prefix = "app.rag.multi-source.claim-vector",
        name = {"enabled", "build-enabled"}, havingValue = "true")
public class ClaimVectorAdminController {

    private final KnowledgeClaimVectorBuildService buildService;
    private final ClaimVectorQualityGate qualityGate;

    public ClaimVectorAdminController(KnowledgeClaimVectorBuildService buildService,
                                      ClaimVectorQualityGate qualityGate) {
        this.buildService = buildService;
        this.qualityGate = qualityGate;
    }

    /** 触发指定项目+业务版本的 Claim 向量构建。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/build")
    public ClaimVectorGenerationManifest build(@Valid @RequestBody BuildRequest request) {
        return buildService.build(request.projectId(), request.businessVersion(), request.buildScope());
    }

    /** 查询当前 ACTIVE 代际；无则 404。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/status")
    public ResponseEntity<ClaimVectorGenerationManifest> status(
            @RequestParam String projectId, @RequestParam String businessVersion) {
        Optional<ClaimVectorGenerationManifest> active =
                buildService.findActive(projectId, businessVersion);
        return active.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /** 质量门检查。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/quality-gate")
    public ClaimVectorQualityGate.QualityGateReport qualityGate(
            @RequestParam String projectId, @RequestParam String businessVersion) {
        return qualityGate.check(projectId, businessVersion);
    }

    /** 回滚到最近退役（上一）代际。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/rollback")
    public ResponseEntity<ClaimVectorGenerationManifest> rollback(@Valid @RequestBody ScopeRequest request) {
        Optional<ClaimVectorGenerationManifest> restored =
                buildService.rollback(request.projectId(), request.businessVersion());
        return restored.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /** 回滚到指定代际。 */
    @RequiresPermission(Permission.WRITE)
    @PostMapping("/rollback-to")
    public ResponseEntity<ClaimVectorGenerationManifest> rollbackTo(@Valid @RequestBody RollbackToRequest request) {
        Optional<ClaimVectorGenerationManifest> restored = buildService.rollbackTo(
                request.projectId(), request.businessVersion(), request.generationId());
        return restored.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /** 构建请求体。 */
    /** buildScope 可选：ACTIVE_DOC（默认）/ ALL_PUBLISHED（全部已发布文档，图/向量补召回用）。 */
    public record BuildRequest(@NotBlank String projectId, @NotBlank String businessVersion,
                               String buildScope) {}

    /** scope 请求体（回滚用）。 */
    public record ScopeRequest(@NotBlank String projectId, @NotBlank String businessVersion) {}

    /** 指定代际回滚请求体。 */
    public record RollbackToRequest(@NotBlank String projectId, @NotBlank String businessVersion,
                                    @NotBlank String generationId) {}
}
