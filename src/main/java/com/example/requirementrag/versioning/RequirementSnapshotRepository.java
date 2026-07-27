package com.example.requirementrag.versioning;

import com.example.requirementrag.config.VersioningProperties;
import com.example.requirementrag.versioning.RequirementSnapshotModels.Entry;
import com.example.requirementrag.versioning.RequirementSnapshotModels.Snapshot;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Reads small, reviewable requirement snapshots that never contain vectors. */
@Repository
public class RequirementSnapshotRepository {
    private final ObjectMapper objectMapper;
    private final Path root;

    public RequirementSnapshotRepository(ObjectMapper objectMapper, VersioningProperties properties) {
        this.objectMapper = objectMapper;
        this.root = Path.of(properties.requirementSnapshotRootPath()).toAbsolutePath().normalize();
    }

    public Optional<Snapshot> findForBusinessVersion(String projectId, String businessVersion) {
        String project = VersionPathPolicy.identifier(projectId, "projectId");
        String version = VersionPathPolicy.identifier(businessVersion, "businessVersion");
        return list(project).stream()
                .filter(snapshot -> version.equals(snapshot.requirementVersion()) || snapshot.aliases().contains(version))
                .findFirst();
    }

    public Optional<Snapshot> find(String projectId, String documentId, String requirementVersion) {
        String project = VersionPathPolicy.identifier(projectId, "projectId");
        String document = VersionPathPolicy.identifier(documentId, "documentId");
        String version = VersionPathPolicy.identifier(requirementVersion, "requirementVersion");
        Path file = VersionPathPolicy.resolveBelow(root, project).resolve(version + ".json").normalize();
        if (!Files.isRegularFile(file)) return Optional.empty();
        Snapshot snapshot = read(file);
        if (!project.equals(snapshot.projectId()) || !document.equals(snapshot.documentId())
                || !version.equals(snapshot.requirementVersion())) return Optional.empty();
        return Optional.of(snapshot);
    }

    public List<Snapshot> list(String projectId) {
        String project = VersionPathPolicy.identifier(projectId, "projectId");
        Path projectRoot = VersionPathPolicy.resolveBelow(root, project);
        if (!Files.isDirectory(projectRoot)) return List.of();
        try (Stream<Path> files = Files.list(projectRoot)) {
            List<Snapshot> snapshots = new ArrayList<>();
            for (Path file : files.filter(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                Snapshot snapshot = read(file);
                if (project.equals(snapshot.projectId())) snapshots.add(snapshot);
            }
            snapshots.sort(Comparator.comparing(RequirementSnapshotModels.Snapshot::requirementVersion,
                    VersionManifestService.versionComparator()));
            return List.copyOf(snapshots);
        } catch (IOException exception) {
            throw unavailable("读取需求版本快照失败", exception);
        }
    }

    Path root() {
        return root;
    }

    private Snapshot read(Path file) {
        try {
            return validate(objectMapper.readValue(Files.readAllBytes(file), Snapshot.class), file);
        } catch (IOException | IllegalArgumentException exception) {
            throw unavailable("读取需求版本快照失败: " + file.getFileName(), exception);
        }
    }

    private Snapshot validate(Snapshot snapshot, Path file) {
        if (snapshot == null || snapshot.schemaVersion() != 1) {
            throw new IllegalArgumentException("不支持的需求快照格式");
        }
        VersionPathPolicy.identifier(snapshot.projectId(), "projectId");
        VersionPathPolicy.identifier(snapshot.documentId(), "documentId");
        VersionPathPolicy.identifier(snapshot.requirementVersion(), "requirementVersion");
        Set<String> entryIds = new HashSet<>();
        for (Entry entry : snapshot.entries()) {
            if (entry == null || !hasText(entry.entryId()) || !hasText(entry.filename())
                    || !hasText(entry.text()) || !hasText(entry.contentHash()) || entry.parentOrder() < 0) {
                throw new IllegalArgumentException("需求快照条目不完整: " + file.getFileName());
            }
            if (!entryIds.add(entry.entryId())) {
                throw new IllegalArgumentException("需求快照 entryId 重复: " + file.getFileName());
            }
        }
        return snapshot;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ResponseStatusException unavailable(String message, Exception cause) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message, cause);
    }
}
