package com.example.requirementrag.wiki;

import com.example.requirementrag.config.WikiProperties;
import com.example.requirementrag.wiki.WikiModels.Page;
import com.example.requirementrag.wiki.WikiModels.ProjectSummary;
import com.example.requirementrag.wiki.WikiModels.VersionIndex;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Reads published Wiki artifacts without depending on Qdrant or model services. */
@Repository
public class WikiRepository {
    private final ObjectMapper objectMapper;
    private final Path root;

    public WikiRepository(ObjectMapper objectMapper, WikiProperties properties) {
        this.objectMapper = objectMapper;
        this.root = Path.of(properties.rootPath()).toAbsolutePath().normalize();
    }

    public List<ProjectSummary> listProjects() {
        if (!Files.isDirectory(root)) return List.of();
        List<ProjectSummary> projects = new ArrayList<>();
        try (Stream<Path> paths = Files.list(root)) {
            for (Path projectPath : paths.filter(Files::isDirectory).sorted().toList()) {
                List<VersionIndex> versions = readIndexes(projectPath);
                if (versions.isEmpty()) continue;
                projects.add(new ProjectSummary(
                        versions.getFirst().projectId(),
                        versions.getFirst().projectName(),
                        versions.stream().map(VersionIndex::version).sorted(versionComparator().reversed()).toList(),
                        versions.stream().mapToInt(index -> index.pages().size()).sum()));
            }
            return List.copyOf(projects);
        } catch (IOException exception) {
            throw unavailable("读取 Wiki 项目失败", exception);
        }
    }

    public List<VersionIndex> listVersions(String projectId) {
        Path projectPath = WikiPathPolicy.resolveBelow(root, projectId);
        if (!Files.isDirectory(projectPath)) return List.of();
        return readIndexes(projectPath).stream()
                .sorted(Comparator.comparing(VersionIndex::version, versionComparator()).reversed())
                .toList();
    }

    public VersionIndex getIndex(String projectId, String version) {
        Path file = WikiPathPolicy.resolveBelow(root, projectId, version).resolve("index.json");
        return read(file, VersionIndex.class, "Wiki 版本不存在: " + projectId + " " + version);
    }

    /** Returns an index when published, without using exceptions for optional comparison sources. */
    public Optional<VersionIndex> findIndex(String projectId, String version) {
        Path file = WikiPathPolicy.resolveBelow(root, projectId, version).resolve("index.json");
        return Files.isRegularFile(file) ? Optional.of(read(file, VersionIndex.class, "Wiki 版本不存在")) : Optional.empty();
    }

    public Page getPage(String projectId, String version, String featureId) {
        Path file = WikiPathPolicy.resolveBelow(root, projectId, version, "pages")
                .resolve(WikiPathPolicy.identifier(featureId, "featureId") + ".json");
        return read(file, Page.class, "Wiki 页面不存在: " + featureId);
    }

    Path root() {
        return root;
    }

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
}
