package com.example.requirementrag.project;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.retrieval.pipeline.RetrievalResultCache;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 业务项目目录查询与旧单仓库项目兼容投影。 */
@Service
public class BusinessProjectCatalogService {

    private final BusinessProjectCatalogStore store;
    private final ProjectRegistry legacyRegistry;
    private final RepositoryVersionResolver versionResolver;
    private final RetrievalResultCache retrievalCache;

    public BusinessProjectCatalogService(BusinessProjectCatalogStore store,
                                         ProjectRegistry legacyRegistry,
                                         RepositoryVersionResolver versionResolver,
                                         RetrievalResultCache retrievalCache) {
        this.store = store;
        this.legacyRegistry = legacyRegistry;
        this.versionResolver = versionResolver;
        this.retrievalCache = retrievalCache;
    }

    public List<BusinessProject> projects() {
        Map<String, BusinessProject> values = new LinkedHashMap<>();
        store.projects().forEach(project -> values.put(project.id(), project));
        for (RagProperties.ProjectConfig legacy : legacyRegistry.all()) {
            if (store.resolveAlias(legacy.id()).isPresent() || store.repository(legacy.id()).isPresent()) continue;
            values.putIfAbsent(legacy.id(), legacyProject(legacy));
        }
        return List.copyOf(values.values());
    }

    public BusinessProject requireProject(String id) {
        String resolved = resolveProjectId(id);
        return store.project(resolved).orElseGet(() -> legacyRegistry.find(resolved)
                .map(this::legacyProject)
                .orElseThrow(() -> new IllegalArgumentException("未知业务项目: " + id)));
    }

    public String resolveProjectId(String id) {
        if (id == null || id.isBlank()) {
            return projects().stream().findFirst().map(BusinessProject::id)
                    .orElseThrow(() -> new IllegalArgumentException("没有可用业务项目"));
        }
        return store.resolveAlias(id).orElse(id);
    }

    public List<CodeRepository> ownedRepositories(String projectId) {
        BusinessProject project = requireProject(projectId);
        List<CodeRepository> persistent = store.ownedRepositories(project.id());
        if (!persistent.isEmpty() || store.project(project.id()).isPresent()) {
            return persistent;
        }
        return legacyRegistry.find(project.id()).map(value -> List.of(legacyRepository(value))).orElse(List.of());
    }

    public List<CodeRepository> sharedRepositories(String projectId) {
        return store.referencedSharedRepositories(requireProject(projectId).id());
    }

    public List<CodeRepository> repositoryScope(String projectId, List<String> requestedRepositoryIds) {
        List<CodeRepository> available = new ArrayList<>();
        available.addAll(ownedRepositories(projectId));
        available.addAll(sharedRepositories(projectId));
        List<CodeRepository> enabled = available.stream().filter(CodeRepository::enabled).toList();
        if (requestedRepositoryIds == null || requestedRepositoryIds.isEmpty()) return enabled;
        var requested = new java.util.LinkedHashSet<>(requestedRepositoryIds);
        List<CodeRepository> filtered = enabled.stream().filter(repository -> requested.contains(repository.id())).toList();
        if (filtered.size() != requested.size()) {
            throw new IllegalArgumentException("仓库筛选包含不属于当前业务项目的仓库");
        }
        return filtered;
    }

    public Optional<CodeRepository> repository(String id) {
        Optional<CodeRepository> persistent = store.repository(id);
        if (persistent.isPresent()) return persistent;
        return legacyRegistry.find(id).map(this::legacyRepository);
    }

    /** 返回业务项目、旧别名或仓库 ID 对应的等价权限范围。 */
    public List<String> accessScopeIds(String id) {
        if (id == null || id.isBlank()) return List.of();
        String normalized = id.trim();
        try {
            BusinessProject project = requireProject(normalized);
            return List.of(project.id(), project.requirementSnapshotNamespace()).stream()
                    .filter(value -> value != null && !value.isBlank()).distinct().toList();
        } catch (IllegalArgumentException ignored) {
            Optional<CodeRepository> repository = repository(normalized);
            if (repository.isPresent() && repository.get().businessProjectId() != null) {
                BusinessProject owner = requireProject(repository.get().businessProjectId());
                return List.of(owner.id(), owner.requirementSnapshotNamespace()).stream()
                        .filter(value -> value != null && !value.isBlank()).distinct().toList();
            }
            return List.of(normalized);
        }
    }

    public RepositoryVersionResolver.ResolvedVersion productVersion(String projectId) {
        BusinessProject project = requireProject(projectId);
        CodeRepository anchor = repository(project.versionAnchorRepositoryId())
                .orElseThrow(() -> new IllegalStateException("业务项目版本主仓库不存在"));
        return versionResolver.resolve(anchor);
    }

    public CoverageStatus requirementCoverage(String projectId) {
        BusinessProject project = requireProject(projectId);
        var version = productVersion(project.id());
        if (!"AVAILABLE".equals(version.status()) || blank(project.latestRequirementVersion())) {
            return CoverageStatus.UNKNOWN;
        }
        return compareVersions(project.latestRequirementVersion(), version.rawVersion()) < 0
                ? CoverageStatus.BEHIND : CoverageStatus.CURRENT;
    }

    public void addSharedReference(String projectId, String repositoryId) {
        BusinessProject project = requirePersistentProject(projectId);
        CodeRepository repository = store.repository(repositoryId)
                .orElseThrow(() -> new IllegalArgumentException("未知公共库: " + repositoryId));
        if (repository.kind() != CodeRepository.Kind.SHARED) {
            throw new IllegalArgumentException("只能引用公共库");
        }
        store.addSharedReference(project.id(), repository.id());
        retrievalCache.invalidateProject(project.id());
    }

    public void removeSharedReference(String projectId, String repositoryId) {
        BusinessProject project = requirePersistentProject(projectId);
        store.removeSharedReference(project.id(), repositoryId);
        retrievalCache.invalidateProject(project.id());
    }

    public BusinessProject requireImportTarget(String projectId) {
        BusinessProject project = requirePersistentProject(projectId);
        if (!project.complete() || project.status() != BusinessProject.Status.ACTIVE) {
            throw new IllegalArgumentException("目标业务项目配置不完整或已停用");
        }
        return project;
    }

    public void registerOwnedRepository(String businessProjectId, String repositoryId, String name,
                                        String side, String codeCollection, String repositoryPath,
                                        String gitPath) {
        BusinessProject project = requireImportTarget(businessProjectId);
        String now = Instant.now().toString();
        store.createRepository(new CodeRepository(repositoryId, name, CodeRepository.Kind.PROJECT,
                project.id(), side, codeCollection, repositoryPath, gitPath,
                "MAVEN_POM", "pom.xml", true, true, now, now));
        retrievalCache.invalidateProject(project.id());
    }

    public void unregisterRepository(String repositoryId) {
        String owner = store.repository(repositoryId).map(CodeRepository::businessProjectId).orElse(null);
        store.deleteRepository(repositoryId);
        retrievalCache.invalidateProject(owner);
    }

    /**
     * 仓库归属版本：owned + shared 仓库集合的稳定哈希，用于缓存按业务项目仓库范围隔离。
     * 引用/解绑/注册/注销后通过 invalidateProject 已全量失效，此处仅用于新 key 的版本区分。
     */
    public String catalogVersion(String projectId) {
        if (projectId == null || projectId.isBlank()) return "0";
        try {
            List<CodeRepository> owned = store.ownedRepositories(projectId);
            List<CodeRepository> shared = store.referencedSharedRepositories(projectId);
            if (owned.isEmpty() && shared.isEmpty() && store.project(projectId).isEmpty()) {
                // legacy project without persistent catalog — use legacy registry size as fallback
                return "legacy:" + legacyRegistry.all().size();
            }
            StringBuilder builder = new StringBuilder();
            for (CodeRepository repo : owned) {
                builder.append(repo.id()).append(':').append(repo.enabled() ? '1' : '0').append(';');
            }
            builder.append('|');
            for (CodeRepository repo : shared) {
                builder.append(repo.id()).append(':').append(repo.enabled() ? '1' : '0').append(';');
            }
            return String.valueOf(builder.toString().hashCode());
        } catch (Exception ignored) {
            return "0";
        }
    }

    BusinessProjectCatalogStore store() {
        return store;
    }

    private BusinessProject requirePersistentProject(String id) {
        String resolved = resolveProjectId(id);
        return store.project(resolved)
                .orElseThrow(() -> new IllegalArgumentException("请先迁移或创建持久化业务项目: " + resolved));
    }

    private BusinessProject legacyProject(RagProperties.ProjectConfig value) {
        RagProperties.ProjectKnowledge knowledge = value.knowledge();
        String documentId = knowledge == null || blank(knowledge.documentId()) ? "unconfigured" : knowledge.documentId();
        String version = knowledge == null ? null : knowledge.version();
        String now = Instant.EPOCH.toString();
        return new BusinessProject(value.id(), value.name(), value.id(),
                value.requirementCollection(), documentId, value.id(), value.id(), version,
                BusinessProject.Status.ACTIVE, now, now);
    }

    private CodeRepository legacyRepository(RagProperties.ProjectConfig value) {
        String now = Instant.EPOCH.toString();
        return new CodeRepository(value.id(), value.name(), CodeRepository.Kind.PROJECT, value.id(),
                value.side(), value.codeCollection(), value.repositoryPath(), value.gitPath(),
                "MAVEN_POM", "pom.xml", false, true, now, now);
    }

    private int compareVersions(String left, String right) {
        int[] leftParts = numericParts(left);
        int[] rightParts = numericParts(right);
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            int leftValue = index < leftParts.length ? leftParts[index] : 0;
            int rightValue = index < rightParts.length ? rightParts[index] : 0;
            if (leftValue != rightValue) return Integer.compare(leftValue, rightValue);
        }
        return 0;
    }

    private int[] numericParts(String value) {
        String normalized = value == null ? "" : value.trim().replaceFirst("^[vV]", "");
        String[] values = normalized.split("[.-]");
        int[] parts = new int[values.length];
        for (int index = 0; index < values.length; index++) {
            try {
                parts[index] = Integer.parseInt(values[index].replaceAll("[^0-9].*$", ""));
            } catch (NumberFormatException exception) {
                parts[index] = 0;
            }
        }
        return parts;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public enum CoverageStatus { CURRENT, BEHIND, UNKNOWN }
}
