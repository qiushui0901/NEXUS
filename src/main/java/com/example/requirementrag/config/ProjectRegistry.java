package com.example.requirementrag.config;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Collections;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 多项目注册表：按 projectId 查找项目配置，解析对应的 Collection 名。
 * 当 projects 列表为空时，自动基于旧版单项目配置构造默认项目。
 */
@Component
public class ProjectRegistry {

    private final RagProperties properties;
    private final Map<String, RagProperties.ProjectConfig> staticProjects;
    private final Map<String, RagProperties.ProjectConfig> dynamicProjects = new LinkedHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 按声明顺序构建 projectId → 项目配置映射，跳过空 id；
     * projects 为空时基于旧版单项目配置构造默认项目，保证向后兼容。
     */
    public ProjectRegistry(RagProperties properties) {
        this.properties = properties;
        Map<String, RagProperties.ProjectConfig> configured = new LinkedHashMap<>();
        for (RagProperties.ProjectConfig project : properties.projects()) {
            if (project.id() == null || project.id().isBlank()) {
                continue;
            }
            configured.put(project.id(), project);
        }
        if (configured.isEmpty()) {
            RagProperties.ProjectConfig fallback = buildFallbackProject();
            configured.put(fallback.id(), fallback);
        }
        this.staticProjects = Collections.unmodifiableMap(new LinkedHashMap<>(configured));
    }

    /**
     * 按 projectId 查找项目配置；projectId 为空时返回默认项目。
     *
     * @return 命中项目的 Optional；非空 id 未命中时为 empty
     */
    public Optional<RagProperties.ProjectConfig> find(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.of(defaultProject());
        }
        lock.readLock().lock();
        try {
            RagProperties.ProjectConfig configured = staticProjects.get(projectId);
            return Optional.ofNullable(configured != null ? configured : dynamicProjects.get(projectId));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 按 projectId 查找项目配置，未命中时抛出异常。
     *
     * @throws IllegalArgumentException 未知项目 id 时
     */
    public RagProperties.ProjectConfig require(String projectId) {
        return find(projectId).orElseThrow(() ->
                new IllegalArgumentException("未知项目: " + projectId));
    }

    /** 返回第一个注册的项目，作为未指定 projectId 时的默认项目。 */
    public RagProperties.ProjectConfig defaultProject() {
        return staticProjects.values().iterator().next();
    }

    /** 返回全部已注册项目（不可变列表，按注册顺序）。 */
    public List<RagProperties.ProjectConfig> all() {
        lock.readLock().lock();
        try {
            Map<String, RagProperties.ProjectConfig> projects = new LinkedHashMap<>(staticProjects);
            dynamicProjects.forEach(projects::putIfAbsent);
            return List.copyOf(projects.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 按 group 查找同一业务组的多个关联项目。 */
    public List<RagProperties.ProjectConfig> findByGroup(String group) {
        if (group == null || group.isBlank()) {
            return List.of();
        }
        return all().stream()
                .filter(p -> group.equals(p.group()))
                .toList();
    }

    /** 解析项目需求文档对应的 collection 名：项目未单独配置时回退到全局 qdrant.collection。 */
    public String resolveRequirementCollection(String projectId) {
        RagProperties.ProjectConfig project = require(projectId);
        String collection = project.requirementCollection();
        return (collection != null && !collection.isBlank()) ? collection : properties.qdrant().collection();
    }

    /**
     * 按需求 collection 反查项目 ID。
     * 显式配置的 collection 唯一归属一个项目；全局默认 collection 可能被多项目共用，
     * 此时返回 empty（调用方应做全量失效，避免只清理默认项目的缓存）。
     */
    public Optional<String> findProjectIdByRequirementCollection(String collection) {
        if (collection == null || collection.isBlank()) {
            return Optional.empty();
        }
        for (RagProperties.ProjectConfig project : all()) {
            if (collection.equals(project.requirementCollection())) {
                return Optional.of(project.id());
            }
        }
        return Optional.empty();
    }

    /** 解析项目代码库对应的 collection 名：项目未单独配置时回退到全局 code.collection。 */
    public String resolveCodeCollection(String projectId) {
        RagProperties.ProjectConfig project = require(projectId);
        String collection = project.codeCollection();
        return (collection != null && !collection.isBlank()) ? collection : properties.code().collection();
    }

    /**
     * 按 Git 路径反查项目 id：先精确匹配 gitPath 与项目 id，
     * 最后退回仅按仓库名（去 namespace 前缀）匹配项目 id，均未命中返回 empty。
     */
    public Optional<String> resolveProjectIdByGitPath(String pathWithNamespace) {
        if (pathWithNamespace == null || pathWithNamespace.isBlank()) {
            return Optional.empty();
        }
        for (RagProperties.ProjectConfig project : all()) {
            if (pathWithNamespace.equals(project.gitPath())) {
                return Optional.of(project.id());
            }
        }
        for (RagProperties.ProjectConfig project : all()) {
            if (pathWithNamespace.equals(project.id())) {
                return Optional.of(project.id());
            }
        }
        String repoName = pathWithNamespace.contains("/")
                ? pathWithNamespace.substring(pathWithNamespace.lastIndexOf('/') + 1)
                : pathWithNamespace;
        for (RagProperties.ProjectConfig project : all()) {
            if (repoName.equals(project.id())) {
                return Optional.of(project.id());
            }
        }
        return Optional.empty();
    }

    /**
     * 注册运行期项目。动态项目不能覆盖静态配置，也不能用不同配置覆盖已有动态项目。
     *
     * @return 新注册返回 true；相同配置已存在返回 false
     */
    public boolean registerDynamic(RagProperties.ProjectConfig project) {
        if (project == null || project.id() == null || project.id().isBlank()) {
            throw new IllegalArgumentException("动态项目 id 不能为空");
        }
        lock.writeLock().lock();
        try {
            if (staticProjects.containsKey(project.id())) {
                throw new IllegalArgumentException("动态项目不能覆盖静态项目: " + project.id());
            }
            RagProperties.ProjectConfig existing = dynamicProjects.get(project.id());
            if (existing != null && !existing.equals(project)) {
                throw new IllegalArgumentException("动态项目已存在: " + project.id());
            }
            return dynamicProjects.putIfAbsent(project.id(), project) == null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 删除运行期项目；静态配置不受影响。 */
    public boolean unregisterDynamic(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        lock.writeLock().lock();
        try {
            return dynamicProjects.remove(projectId) != null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 判断项目 id 是否来自启动时静态配置。 */
    public boolean isStaticProject(String projectId) {
        return projectId != null && staticProjects.containsKey(projectId);
    }

    /** 基于旧版单项目配置构造一个默认 ProjectConfig，保持向后兼容。 */
    private RagProperties.ProjectConfig buildFallbackProject() {
        RagProperties.Knowledge k = properties.knowledge();
        RagProperties.Code c = properties.code();
        return new RagProperties.ProjectConfig(
                c.projectId(),
                c.projectId(),
                c.projectId(),
                "server",
                properties.qdrant().collection(),
                c.collection(),
                c.repositoryPath(),
                null,
                new RagProperties.ProjectKnowledge(
                        k.bootstrapEnabled(), k.zipPath(), k.xlsxPath(),
                        k.documentId(), k.version(), k.zipFolderPrefix(),
                        k.xlsxSheetPrefix(), k.minHtmlBytes()),
                c.includePathSubstrings(),
                c.excludePathSubstrings(),
                c.maxFileBytes());
    }
}
