package com.example.requirementrag.web;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.Permission;
import com.example.requirementrag.versioning.VersionComparisonService;
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

/** Manages version baselines and compares requirement, code, test and Wiki sources. */
@RestController
@RequestMapping("/api/versions")
public class VersionController {
    private final VersionManifestService manifests;
    private final VersionComparisonService comparisons;
    private final ProjectRegistry projectRegistry;
    private final ProjectAccessGuard accessGuard;

    public VersionController(VersionManifestService manifests, VersionComparisonService comparisons,
                             ProjectRegistry projectRegistry, ProjectAccessGuard accessGuard) {
        this.manifests = manifests;
        this.comparisons = comparisons;
        this.projectRegistry = projectRegistry;
        this.accessGuard = accessGuard;
    }

    @RequiresPermission(Permission.WRITE)
    @PutMapping("/manifests")
    public VersionManifest save(@Valid @RequestBody VersionManifest manifest, HttpServletRequest request) {
        requireAccess(manifest.projectId(), request);
        return manifests.save(manifest);
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/manifests")
    public List<VersionManifest> list(@RequestParam String projectId, HttpServletRequest request) {
        requireAccess(projectId, request);
        return manifests.list(projectId);
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/manifests/{version}")
    public VersionManifest get(@PathVariable String version, @RequestParam String projectId,
                               HttpServletRequest request) {
        requireAccess(projectId, request);
        return manifests.get(projectId, version);
    }

    @RequiresPermission(Permission.PUBLIC_READ)
    @GetMapping("/compare")
    public VersionComparisonReport compare(@RequestParam String projectId,
                                           @RequestParam String fromVersion,
                                           @RequestParam String toVersion,
                                           HttpServletRequest request) {
        requireAccess(projectId, request);
        return comparisons.compare(projectId, fromVersion, toVersion);
    }

    private void requireAccess(String projectId, HttpServletRequest request) {
        projectRegistry.require(projectId);
        accessGuard.requireProjectAccess(request, projectId);
    }
}
