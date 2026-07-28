package com.example.requirementrag.knowledge.build;

import com.example.requirementrag.config.WikiProperties;
import com.example.requirementrag.knowledge.build.KnowledgeDraftModels.AuditEntry;
import com.example.requirementrag.knowledge.build.KnowledgeDraftModels.DraftMetadata;
import com.example.requirementrag.knowledge.build.KnowledgeDraftModels.DraftStatus;
import com.example.requirementrag.knowledge.build.KnowledgeDraftModels.Publication;
import com.example.requirementrag.knowledge.build.KnowledgeDraftModels.PublishResult;
import com.example.requirementrag.knowledge.build.KnowledgeDraftModels.RollbackResult;
import com.example.requirementrag.wiki.WikiGenerationService;
import com.example.requirementrag.wiki.WikiModels.GenerationResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** File-backed state machine for reviewable version-knowledge drafts. */
@Service
public class KnowledgeDraftLifecycleService {
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Map<DraftStatus, EnumSet<DraftStatus>> TRANSITIONS = Map.of(
            DraftStatus.DRAFT, EnumSet.of(DraftStatus.IN_REVIEW),
            DraftStatus.IN_REVIEW, EnumSet.of(DraftStatus.APPROVED, DraftStatus.REJECTED,
                    DraftStatus.SPLIT, DraftStatus.MERGED),
            DraftStatus.REJECTED, EnumSet.of(DraftStatus.IN_REVIEW)
    );

    private final ObjectMapper objectMapper;
    private final WikiGenerationService wikiGenerationService;
    private final Path draftRoot;
    private final Path sourceRoot;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public KnowledgeDraftLifecycleService(ObjectMapper objectMapper, WikiProperties properties,
                                          WikiGenerationService wikiGenerationService) {
        this.objectMapper = objectMapper;
        this.wikiGenerationService = wikiGenerationService;
        this.draftRoot = Path.of(properties.draftPath()).toAbsolutePath().normalize();
        this.sourceRoot = Path.of(properties.sourcePath()).toAbsolutePath().normalize();
    }

    public DraftMetadata initializeDraft(String projectId, String version, String buildId,
                                         String actor, String createdAt) {
        Path directory = draftDirectory(projectId, version, buildId);
        if (!Files.isDirectory(directory)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识草稿不存在");
        }
        synchronized (lock(projectId, version, buildId)) {
            Path metadataFile = directory.resolve("review.json");
            if (Files.isRegularFile(metadataFile)) return read(metadataFile);
            String now = text(createdAt).isBlank() ? Instant.now().toString() : createdAt.trim();
            DraftMetadata metadata = new DraftMetadata(identifier(buildId, "buildId"),
                    identifier(projectId, "projectId"), identifier(version, "version"), DraftStatus.DRAFT,
                    0, now, now, actor(actor),
                    List.of(new AuditEntry(null, DraftStatus.DRAFT, actor(actor), now, "草稿已创建")), null);
            writeAtomic(metadataFile, metadata);
            return metadata;
        }
    }

    public List<DraftMetadata> list(String projectId, String version) {
        Path versionDirectory = versionDirectory(projectId, version);
        if (!Files.isDirectory(versionDirectory)) return List.of();
        try (Stream<Path> children = Files.list(versionDirectory)) {
            return children.filter(Files::isDirectory)
                    .map(path -> path.resolve("review.json"))
                    .filter(Files::isRegularFile)
                    .map(this::read)
                    .sorted(Comparator.comparing(DraftMetadata::createdAt).reversed())
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("知识草稿列表读取失败");
        }
    }

    public DraftMetadata get(String projectId, String version, String buildId) {
        Path file = draftDirectory(projectId, version, buildId).resolve("review.json");
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识草稿不存在");
        }
        return read(file);
    }

    public DraftMetadata transition(String projectId, String version, String buildId,
                                    DraftStatus target, String actor, String comment) {
        if (target == null) throw new IllegalArgumentException("targetStatus 不能为空");
        if (target == DraftStatus.PUBLISHED) {
            throw new IllegalArgumentException("PUBLISHED 只能通过发布操作进入");
        }
        synchronized (lock(projectId, version, buildId)) {
            DraftMetadata current = get(projectId, version, buildId);
            if (!TRANSITIONS.getOrDefault(current.status(), EnumSet.noneOf(DraftStatus.class)).contains(target)) {
                throw new IllegalArgumentException("非法草稿状态转换: " + current.status() + " -> " + target);
            }
            return update(current, target, actor, comment, current.publication());
        }
    }

    public PublishResult publish(String projectId, String version, String buildId,
                                 String actor, String comment) {
        synchronized (lock(projectId, version, buildId)) {
            DraftMetadata current = get(projectId, version, buildId);
            if (current.status() != DraftStatus.APPROVED) {
                throw new IllegalArgumentException("只有 APPROVED 草稿可以发布");
            }
            Path draftDirectory = draftDirectory(projectId, version, buildId);
            rejectNoChangesDraft(draftDirectory.resolve("build.json"));
            Path draftSource = draftDirectory.resolve("wiki-source.json");
            if (!Files.isRegularFile(draftSource)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "草稿 Wiki 源定义不存在");
            }
            Path formalSource = formalSource(projectId, version);
            String publicationId = Instant.now().toString().replaceAll("[^0-9]", "").substring(0, 14)
                    + "-" + UUID.randomUUID().toString().substring(0, 8);
            Path snapshot = snapshot(projectId, version, publicationId);
            boolean hadPrevious = Files.isRegularFile(formalSource);
            try {
                Files.createDirectories(formalSource.getParent());
                if (hadPrevious) atomicCopy(formalSource, snapshot);
                atomicCopy(draftSource, formalSource);
                GenerationResult generated = wikiGenerationService.generate(projectId, version);
                String now = Instant.now().toString();
                Publication publication = new Publication(publicationId, now, actor(actor),
                        hadPrevious ? publicationId : "", "", "", "");
                DraftMetadata published = update(current, DraftStatus.PUBLISHED, actor, comment, publication);
                return new PublishResult(published, generated);
            } catch (RuntimeException | IOException exception) {
                RuntimeException failure = exception instanceof RuntimeException runtime ? runtime
                        : new IllegalStateException("知识草稿发布失败", exception);
                restoreAfterFailedPublish(formalSource, snapshot, hadPrevious, projectId, version, failure);
                throw failure;
            }
        }
    }

    public RollbackResult rollback(String projectId, String version, String buildId,
                                   String actor, String comment) {
        synchronized (lock(projectId, version, buildId)) {
            DraftMetadata current = get(projectId, version, buildId);
            Publication publication = current.publication();
            if (current.status() != DraftStatus.PUBLISHED || publication == null
                    || text(publication.previousSnapshotId()).isBlank()) {
                throw new IllegalArgumentException("该草稿没有可回滚的上一份正式快照");
            }
            if (!text(publication.rolledBackAt()).isBlank()) {
                throw new IllegalArgumentException("该发布已回滚，不可重复回滚");
            }
            Path formalSource = formalSource(projectId, version);
            Path previous = snapshot(projectId, version, publication.previousSnapshotId());
            if (!Files.isRegularFile(previous)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "上一份正式快照不存在");
            }
            Path currentBackup = formalSource.resolveSibling("." + formalSource.getFileName()
                    + ".rollback-" + UUID.randomUUID());
            try {
                atomicCopy(formalSource, currentBackup);
                atomicCopy(previous, formalSource);
                GenerationResult generated = wikiGenerationService.generate(projectId, version);
                Files.deleteIfExists(currentBackup);
                String now = Instant.now().toString();
                Publication rolledBack = new Publication(publication.publicationId(), publication.publishedAt(),
                        publication.publishedBy(), publication.previousSnapshotId(), now, actor(actor), text(comment));
                DraftMetadata metadata = appendAudit(current, actor, comment, rolledBack, now);
                return new RollbackResult(metadata, generated);
            } catch (RuntimeException | IOException exception) {
                RuntimeException failure = exception instanceof RuntimeException runtime ? runtime
                        : new IllegalStateException("知识 Wiki 回滚失败", exception);
                restoreRollbackBackup(currentBackup, formalSource, projectId, version, failure);
                throw failure;
            }
        }
    }

    private DraftMetadata update(DraftMetadata current, DraftStatus target, String actor,
                                 String comment, Publication publication) {
        String now = Instant.now().toString();
        List<AuditEntry> history = new ArrayList<>(current.history());
        history.add(new AuditEntry(current.status(), target, actor(actor), now, text(comment)));
        DraftMetadata updated = new DraftMetadata(current.buildId(), current.projectId(), current.version(), target,
                current.revision() + 1, current.createdAt(), now, current.createdBy(), history, publication);
        writeAtomic(draftDirectory(current.projectId(), current.version(), current.buildId()).resolve("review.json"), updated);
        return updated;
    }

    private DraftMetadata appendAudit(DraftMetadata current, String actor, String comment,
                                      Publication publication, String now) {
        List<AuditEntry> history = new ArrayList<>(current.history());
        history.add(new AuditEntry(DraftStatus.PUBLISHED, DraftStatus.PUBLISHED, actor(actor), now,
                text(comment).isBlank() ? "已回滚到上一份正式快照" : text(comment)));
        DraftMetadata updated = new DraftMetadata(current.buildId(), current.projectId(), current.version(),
                current.status(), current.revision() + 1, current.createdAt(), now, current.createdBy(), history, publication);
        writeAtomic(draftDirectory(current.projectId(), current.version(), current.buildId()).resolve("review.json"), updated);
        return updated;
    }

    private void restoreAfterFailedPublish(Path formalSource, Path snapshot, boolean hadPrevious,
                                           String projectId, String version, RuntimeException originalFailure) {
        try {
            if (hadPrevious && Files.isRegularFile(snapshot)) {
                atomicCopy(snapshot, formalSource);
                wikiGenerationService.generate(projectId, version);
            } else {
                Files.deleteIfExists(formalSource);
            }
        } catch (RuntimeException | IOException restorationFailure) {
            originalFailure.addSuppressed(restorationFailure);
        }
    }

    private void restoreRollbackBackup(Path backup, Path formalSource, String projectId, String version,
                                       RuntimeException originalFailure) {
        try {
            if (Files.isRegularFile(backup)) {
                atomicCopy(backup, formalSource);
                wikiGenerationService.generate(projectId, version);
                Files.deleteIfExists(backup);
            }
        } catch (RuntimeException | IOException restorationFailure) {
            originalFailure.addSuppressed(restorationFailure);
        }
    }

    private void rejectNoChangesDraft(Path buildFile) {
        if (!Files.isRegularFile(buildFile)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识草稿构建产物不存在");
        }
        try {
            JsonNode build = objectMapper.readTree(Files.readAllBytes(buildFile));
            if (build != null && "NO_CHANGES".equals(build.path("status").asText())) {
                throw new IllegalArgumentException("NO_CHANGES 草稿不可发布");
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new IllegalStateException("知识草稿构建产物无法解析", exception);
        }
    }

    private DraftMetadata read(Path file) {
        try {
            return objectMapper.readValue(Files.readAllBytes(file), DraftMetadata.class);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("知识草稿元数据无法解析");
        }
    }

    private void writeAtomic(Path file, DraftMetadata value) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling("." + file.getFileName() + ".next-" + UUID.randomUUID());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + System.lineSeparator();
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            moveReplace(temporary, file);
        } catch (IOException exception) {
            throw new IllegalStateException("知识草稿元数据写入失败");
        }
    }

    private void atomicCopy(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling("." + target.getFileName() + ".next-" + UUID.randomUUID());
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            moveReplace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path versionDirectory(String projectId, String version) {
        return below(draftRoot, identifier(projectId, "projectId"), identifier(version, "version"));
    }

    private Path draftDirectory(String projectId, String version, String buildId) {
        return below(draftRoot, identifier(projectId, "projectId"), identifier(version, "version"),
                identifier(buildId, "buildId"));
    }

    private Path formalSource(String projectId, String version) {
        String filename = identifier(projectId, "projectId") + "-v" + identifier(version, "version") + ".json";
        return below(sourceRoot, filename);
    }

    private Path snapshot(String projectId, String version, String snapshotId) {
        return below(draftRoot, ".publication-history", identifier(projectId, "projectId"),
                identifier(version, "version"), identifier(snapshotId, "snapshotId") + ".json");
    }

    private Path below(Path root, String... parts) {
        Path result = root;
        for (String part : parts) result = result.resolve(part);
        result = result.normalize();
        if (!result.startsWith(root)) throw new IllegalArgumentException("不安全的知识草稿路径");
        return result;
    }

    private Object lock(String projectId, String version, String buildId) {
        return locks.computeIfAbsent(identifier(projectId, "projectId") + '/' + identifier(version, "version")
                + '/' + identifier(buildId, "buildId"), ignored -> new Object());
    }

    private String identifier(String value, String field) {
        String normalized = text(value).trim();
        if (!SAFE_IDENTIFIER.matcher(normalized).matches() || normalized.contains("..")) {
            throw new IllegalArgumentException(field + " contains unsafe characters");
        }
        return normalized;
    }

    private String actor(String value) {
        return text(value).isBlank() ? "system" : value.trim();
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}
