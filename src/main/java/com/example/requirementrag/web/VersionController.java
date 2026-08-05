package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.versioning.VersionComparisonService;
import com.example.requirementrag.versioning.VersionManifestResolver;
import com.example.requirementrag.versioning.VersionManifestService;
import com.example.requirementrag.versioning.VersionModels.VersionComparisonReport;
import com.example.requirementrag.versioning.VersionModels.VersionManifest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 版本基线管理接口：保存与查询基线，并对比需求、代码、测试与 Wiki 来源。 */
@RestController
@RequestMapping("/api/versions")
public class VersionController {
    private final VersionManifestService manifests;
    private final VersionManifestResolver manifestResolver;
    private final VersionComparisonService comparisons;
    private final ProjectRegistry projectRegistry;
    private final ProjectAccessGuard accessGuard;

    public VersionController(VersionManifestService manifests, VersionManifestResolver manifestResolver,
                             VersionComparisonService comparisons, ProjectRegistry projectRegistry,
                             ProjectAccessGuard accessGuard) {
        this.manifests = manifests;
        this.manifestResolver = manifestResolver;
        this.comparisons = comparisons;
        this.projectRegistry = projectRegistry;
        this.accessGuard = accessGuard;
    }

    /** 保存版本基线清单。对应 PUT /api/versions/manifests。 */
    @RequiresPermission(Permission.WRITE)
    @PutMapping("/manifests")
    public VersionManifest save(@Valid @RequestBody VersionManifest manifest, HttpServletRequest request) {
        requireAccess(manifest.projectId(), request);
        return manifests.save(manifest);
    }

    /** 列出指定项目的全部版本基线。对应 GET /api/versions/manifests。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/manifests")
    public List<VersionManifest> list(@RequestParam String projectId, HttpServletRequest request) {
        requireAccess(projectId, request);
        return manifestResolver.list(projectId);
    }

    /** 获取指定版本号的基线清单。对应 GET /api/versions/manifests/{version}。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/manifests/{version}")
    public VersionManifest get(@PathVariable String version, @RequestParam String projectId,
                               HttpServletRequest request) {
        requireAccess(projectId, request);
        return manifestResolver.get(projectId, version);
    }

    /** 对比 fromVersion 与 toVersion 的差异并生成报告。对应 GET /api/versions/compare。 */
    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/compare")
    public VersionComparisonReport compare(@RequestParam String projectId,
                                           @RequestParam String fromVersion,
                                           @RequestParam String toVersion,
                                           HttpServletRequest request) {
        requireAccess(projectId, request);
        return comparisons.compare(projectId, fromVersion, toVersion);
    }

    /** 校验项目存在且当前用户有访问权。 */
    private void requireAccess(String projectId, HttpServletRequest request) {
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(request, projectId);
    }
}
