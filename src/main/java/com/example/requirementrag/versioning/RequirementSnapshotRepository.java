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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Reads and materializes small, reviewable requirement snapshots that never contain vectors. */
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

    /** Builds the complete requirement state by replaying incremental snapshots from the baseline forward. */
    public Optional<Snapshot> materialize(String projectId, String documentId, String requirementVersion) {
        String project = VersionPathPolicy.identifier(projectId, "projectId");
        String document = VersionPathPolicy.identifier(documentId, "documentId");
        String version = VersionPathPolicy.identifier(requirementVersion, "requirementVersion");
        Map<String, Snapshot> snapshotsByVersion = new HashMap<>();
        for (Snapshot snapshot : list(project)) snapshotsByVersion.put(snapshot.requirementVersion(), snapshot);
        Snapshot target = snapshotsByVersion.get(version);
        if (target == null || !document.equals(target.documentId())) return Optional.empty();
        return Optional.of(materialize(target, snapshotsByVersion, new HashSet<>(), new HashMap<>()));
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

    private Snapshot materialize(Snapshot snapshot, Map<String, Snapshot> snapshotsByVersion,
                                 Set<String> visiting, Map<String, Snapshot> cache) {
        String key = snapshot.documentId() + "@" + snapshot.requirementVersion();
        Snapshot cached = cache.get(key);
        if (cached != null) return cached;
        if (!visiting.add(key)) {
            throw unavailable("需求快照继承链存在循环", new IllegalArgumentException(key));
        }
        try {
            LinkedHashMap<String, Entry> active = new LinkedHashMap<>();
            if (hasText(snapshot.baseRequirementVersion())) {
                Snapshot baseline = snapshotsByVersion.get(snapshot.baseRequirementVersion());
                if (baseline == null) {
                    throw unavailable("需求快照基线不存在: " + snapshot.requirementVersion(),
                            new IllegalArgumentException(snapshot.baseRequirementVersion()));
                }
                if (!snapshot.documentId().equals(baseline.documentId())) {
                    throw unavailable("需求快照基线文档不一致: " + snapshot.requirementVersion(),
                            new IllegalArgumentException(snapshot.baseRequirementVersion()));
                }
                for (Entry entry : materialize(baseline, snapshotsByVersion, visiting, cache).entries()) {
                    active.put(entry.entryId(), entry);
                }
            }
            for (Entry entry : snapshot.entries()) {
                switch (entry.effectiveOperation()) {
                    case UPSERT -> active.put(entry.entryId(), entry);
                    case REMOVE -> {
                        if (active.remove(entry.entryId()) == null) {
                            throw unavailable("显式删除引用了不存在的历史需求: " + snapshot.requirementVersion(),
                                    new IllegalArgumentException(entry.entryId()));
                        }
                    }
                }
            }
            Snapshot result = new Snapshot(snapshot.schemaVersion(), snapshot.projectId(), snapshot.documentId(),
                    snapshot.requirementVersion(), snapshot.baseRequirementVersion(), snapshot.aliases(),
                    snapshot.generatedAt(), snapshot.sources(), List.copyOf(active.values()));
            cache.put(key, result);
            return result;
        } finally {
            visiting.remove(key);
        }
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
        if (hasText(snapshot.baseRequirementVersion())) {
            VersionPathPolicy.identifier(snapshot.baseRequirementVersion(), "baseRequirementVersion");
        }
        for (String alias : snapshot.aliases()) VersionPathPolicy.identifier(alias, "alias");
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
