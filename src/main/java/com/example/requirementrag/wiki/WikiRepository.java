package com.example.requirementrag.wiki;

import com.example.requirementrag.cache.BoundedTtlCache;
import com.example.requirementrag.config.WikiProperties;
import com.example.requirementrag.wiki.WikiModels.Page;
import com.example.requirementrag.wiki.WikiModels.ProjectSummary;
import com.example.requirementrag.wiki.WikiModels.VersionIndex;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** 读取已发布的 Wiki 产物，不依赖 Qdrant 或模型服务。 */
@Repository
public class WikiRepository {
    private final ObjectMapper objectMapper;
    private final Path root;
    private final BoundedTtlCache<IndexKey, VersionIndex> indexCache;
    private final BoundedTtlCache<PageKey, Page> pageCache;

    public WikiRepository(ObjectMapper objectMapper, WikiProperties properties) {
        this.objectMapper = objectMapper;
        this.root = Path.of(properties.rootPath()).toAbsolutePath().normalize();
        Duration ttl = Duration.ofSeconds(properties.cacheTtlSeconds());
        this.indexCache = new BoundedTtlCache<>(ttl, properties.cacheMaxEntries());
        this.pageCache = new BoundedTtlCache<>(ttl, properties.cacheMaxEntries());
    }

    /**
     * 列出所有发布过 Wiki 的项目概览，版本按版本号降序。
     *
     * @return 项目概览列表
     */
    public List<ProjectSummary> listProjects() {
        if (!Files.isDirectory(root)) return List.of();
        List<ProjectSummary> projects = new ArrayList<>();
        try (Stream<Path> paths = Files.list(root)) {
            for (Path projectPath : paths.filter(Files::isDirectory).sorted().toList()) {
                List<VersionIndex> versions = readIndexes(projectPath);
                if (versions.isEmpty()) continue;
                projects.add(new ProjectSummary(
                        versions.get(0).projectId(),
                        versions.get(0).projectName(),
                        versions.stream().map(VersionIndex::version).sorted(versionComparator().reversed()).toList(),
                        versions.stream().mapToInt(index -> index.pages().size()).sum()));
            }
            return List.copyOf(projects);
        } catch (IOException exception) {
            throw unavailable("读取 Wiki 项目失败", exception);
        }
    }

    /**
     * 列出项目全部已发布版本的索引，按版本号降序。
     *
     * @param projectId 项目标识
     * @return 版本索引列表
     */
    public List<VersionIndex> listVersions(String projectId) {
        Path projectPath = WikiPathPolicy.resolveBelow(root, projectId);
        if (!Files.isDirectory(projectPath)) return List.of();
        try (Stream<Path> paths = Files.list(projectPath)) {
            return paths.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(version -> Files.isRegularFile(pathForIndex(projectId, version)))
                    .map(version -> getIndex(projectId, version))
                    .sorted(Comparator.comparing(VersionIndex::version, versionComparator()).reversed())
                    .toList();
        } catch (IOException exception) {
            throw unavailable("读取 Wiki 版本失败", exception);
        }
    }

    /**
     * 读取指定版本的索引（带 TTL 缓存），版本不存在时返回 404。
     *
     * @param projectId 项目标识
     * @param version   版本号
     * @return 版本索引
     */
    public VersionIndex getIndex(String projectId, String version) {
        IndexKey key = new IndexKey(projectId, version);
        Optional<VersionIndex> cached = indexCache.get(key);
        if (cached.isPresent()) return cached.get();
        Path file = WikiPathPolicy.resolveBelow(root, projectId, version).resolve("index.json");
        VersionIndex value = read(file, VersionIndex.class, "Wiki 版本不存在: " + projectId + " " + version);
        indexCache.put(key, value);
        return value;
    }

    /** 索引已发布时返回它，不用异常表达可选比对来源的缺失。 */
    public Optional<VersionIndex> findIndex(String projectId, String version) {
        Path file = WikiPathPolicy.resolveBelow(root, projectId, version).resolve("index.json");
        return Files.isRegularFile(file) ? Optional.of(getIndex(projectId, version)) : Optional.empty();
    }

    /**
     * 读取指定版本的页面（带 TTL 缓存），页面不存在时返回 404。
     *
     * @param projectId 项目标识
     * @param version   版本号
     * @param featureId 功能标识
     * @return 页面模型
     */
    public Page getPage(String projectId, String version, String featureId) {
        PageKey key = new PageKey(projectId, version, featureId);
        Optional<Page> cached = pageCache.get(key);
        if (cached.isPresent()) return cached.get();
        Path file = WikiPathPolicy.resolveBelow(root, projectId, version, "pages")
                .resolve(WikiPathPolicy.identifier(featureId, "featureId") + ".json");
        Page value = read(file, Page.class, "Wiki 页面不存在: " + featureId);
        pageCache.put(key, value);
        return value;
    }

    /** 发布原子替换后，仅失效该 项目/版本 的缓存。 */
    public void invalidate(String projectId, String version) {
        indexCache.invalidate(new IndexKey(projectId, version));
        pageCache.invalidateWhere(key -> key.projectId().equals(projectId) && key.version().equals(version));
    }

    Path root() {
        return root;
    }

    private Path pathForIndex(String projectId, String version) {
        return WikiPathPolicy.resolveBelow(root, projectId, version).resolve("index.json");
    }

    /** 语义化版本号比较器：逐段按整数比较，非数字段按字典序兜底。 */
    private Comparator<String> versionComparator() {
        return (left, right) -> {
            String[] leftParts = left.split("\\.");
            String[] rightParts = right.split("\\.");
            int length = Math.max(leftParts.length, rightParts.length);
            for (int index = 0; index < length; index++) {
                int leftPart = versionPart(leftParts, index);
                int rightPart = versionPart(rightParts, index);
                int comparison = Integer.compare(leftPart, rightPart);
                if (comparison != 0) return comparison;
            }
            return left.compareTo(right);
        };
    }

    private int versionPart(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /** 读取项目目录下各版本子目录中的 index.json。 */
    private List<VersionIndex> readIndexes(Path projectPath) {
        try (Stream<Path> paths = Files.list(projectPath)) {
            List<VersionIndex> result = new ArrayList<>();
            for (Path versionPath : paths.filter(Files::isDirectory).toList()) {
                Path index = versionPath.resolve("index.json");
                if (Files.isRegularFile(index)) {
                    result.add(objectMapper.readValue(Files.readAllBytes(index), VersionIndex.class));
                }
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            throw unavailable("读取 Wiki 版本失败", exception);
        }
    }

    /** 读取 JSON 文件为指定类型：文件缺失返回 404，解析失败返回 500。 */
    private <T> T read(Path file, Class<T> type, String notFoundMessage) {
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFoundMessage);
        }
        try {
            return objectMapper.readValue(Files.readAllBytes(file), type);
        } catch (IOException exception) {
            throw unavailable("读取 Wiki 文件失败: " + file.getFileName(), exception);
        }
    }

    private ResponseStatusException unavailable(String message, Exception cause) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message, cause);
    }

    private record IndexKey(String projectId, String version) {
    }

    private record PageKey(String projectId, String version, String featureId) {
    }
}
