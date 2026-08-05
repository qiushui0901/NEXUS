package com.example.requirementrag.config;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 多项目注册表：按 projectId 查找项目配置，解析对应的 Collection 名。
 * 当 projects 列表为空时，自动基于旧版单项目配置构造默认项目。
 */
@Component
public class ProjectRegistry {

    private final RagProperties properties;
    private final Map<String, RagProperties.ProjectConfig> projectMap;

    /**
     * 按声明顺序构建 projectId → 项目配置映射，跳过空 id；
     * projects 为空时基于旧版单项目配置构造默认项目，保证向后兼容。
     */
    public ProjectRegistry(RagProperties properties) {
        this.properties = properties;
        this.projectMap = new LinkedHashMap<>();
        for (RagProperties.ProjectConfig project : properties.projects()) {
            if (project.id() == null || project.id().isBlank()) {
                continue;
            }
            projectMap.put(project.id(), project);
        }
        if (projectMap.isEmpty()) {
            RagProperties.ProjectConfig fallback = buildFallbackProject();
            projectMap.put(fallback.id(), fallback);
        }
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
        return Optional.ofNullable(projectMap.get(projectId));
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
        return projectMap.values().iterator().next();
    }

    /** 返回全部已注册项目（不可变列表，按注册顺序）。 */
    public List<RagProperties.ProjectConfig> all() {
        return List.copyOf(projectMap.values());
    }

    /** 按 group 查找同一业务组的多个关联项目。 */
    public List<RagProperties.ProjectConfig> findByGroup(String group) {
        if (group == null || group.isBlank()) {
            return List.of();
        }
        return projectMap.values().stream()
                .filter(p -> group.equals(p.group()))
                .toList();
    }

    /** 解析项目需求文档对应的 collection 名：项目未单独配置时回退到全局 qdrant.collection。 */
    public String resolveRequirementCollection(String projectId) {
        RagProperties.ProjectConfig project = require(projectId);
        String collection = project.requirementCollection();
        return (collection != null && !collection.isBlank()) ? collection : properties.qdrant().collection();
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
        for (RagProperties.ProjectConfig project : projectMap.values()) {
            if (pathWithNamespace.equals(project.gitPath())) {
                return Optional.of(project.id());
            }
        }
        for (RagProperties.ProjectConfig project : projectMap.values()) {
            if (pathWithNamespace.equals(project.id())) {
                return Optional.of(project.id());
            }
        }
        String repoName = pathWithNamespace.contains("/")
                ? pathWithNamespace.substring(pathWithNamespace.lastIndexOf('/') + 1)
                : pathWithNamespace;
        for (RagProperties.ProjectConfig project : projectMap.values()) {
            if (repoName.equals(project.id())) {
                return Optional.of(project.id());
            }
        }
        return Optional.empty();
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
