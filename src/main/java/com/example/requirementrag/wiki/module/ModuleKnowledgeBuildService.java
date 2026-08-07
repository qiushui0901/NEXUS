package com.example.requirementrag.wiki.module;

import com.example.requirementrag.config.WikiProperties;
import com.example.requirementrag.knowledge.build.KnowledgeBuildModels.BuildStatus;
import com.example.requirementrag.knowledge.build.KnowledgeDraftLifecycleService;
import com.example.requirementrag.knowledge.build.KnowledgeDraftModels.DraftMetadata;
import com.example.requirementrag.wiki.WikiModels.PageSource;
import com.example.requirementrag.wiki.WikiModels.VersionSource;
import com.example.requirementrag.wiki.module.ModuleFactModels.ModuleBuildRequest;
import com.example.requirementrag.wiki.module.ModuleFactModels.ModuleFactBundle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 模块知识构建：抽取事实 → 规划页面 → 质量门 → 保存可审核草稿；不自动发布。 */
@Service
public class ModuleKnowledgeBuildService {
    private static final Logger log = LoggerFactory.getLogger(ModuleKnowledgeBuildService.class);
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern FORBIDDEN_FIELD = Pattern.compile(
            "(?i)\\\"(?:vector|vectors|denseVector|sparseVector|embedding|embeddings|qdrantPoint|qdrantPoints|"
                    + "snapshot|snapshots|storage|apiKey|password|secret|token|authorization|credential|credentials)"
                    + "\\\"\\s*:");

    private final ObjectMapper objectMapper;
    private final ModuleFactExtractor extractor;
    private final ModuleWikiPlanner planner;
    private final ModuleClaimQualityGate qualityGate;
    private final KnowledgeDraftLifecycleService draftLifecycleService;
    private final Path draftRoot;

    public ModuleKnowledgeBuildService(ObjectMapper objectMapper, WikiProperties properties,
                                       ModuleFactExtractor extractor, ModuleWikiPlanner planner,
                                       ModuleClaimQualityGate qualityGate,
                                       KnowledgeDraftLifecycleService draftLifecycleService) {
        this.objectMapper = objectMapper;
        this.extractor = extractor;
        this.planner = planner;
        this.qualityGate = qualityGate;
        this.draftLifecycleService = draftLifecycleService;
        this.draftRoot = Path.of(properties.draftPath()).toAbsolutePath().normalize();
    }

    /** 执行一次模块知识构建：抽取事实、规划页面、过质量门、落盘草稿并初始化审核元数据。 */
    public DraftMetadata build(ModuleBuildRequest request) {
        String projectId = identifier(request.projectId(), "projectId");
        String version = identifier(request.version(), "version");
        String modulePath = request.modulePath() == null ? "" : request.modulePath().trim();
        if (modulePath.isEmpty()) throw new IllegalArgumentException("modulePath 不能为空");

        ModuleFactBundle bundle = extractor.extract(projectId, modulePath, version);
        PageSource page = planner.plan(bundle, version, null, request.codeCommit());
        qualityGate.validate(projectId, version, List.of(page));

        String buildId = Instant.now().toString().replaceAll("[^0-9]", "").substring(0, 14)
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        String generatedAt = Instant.now().toString();
        VersionSource wikiSource = new VersionSource(2, projectId, projectId, version, version,
                "", request.codeCommit() == null ? text(bundle.commitSha()) : request.codeCommit(),
                generatedAt, List.of(page));
        Path target = writeDraft(projectId, version, buildId, wikiSource, bundle);
        return draftLifecycleService.initializeDraft(projectId, version, buildId,
                text(request.actor()).isBlank() ? "system" : request.actor(), generatedAt);
    }

    /** 以暂存目录方式原子落盘 wiki-source.json 与 module-bundle.json，整体移入目标草稿目录。 */
    private Path writeDraft(String projectId, String version, String buildId,
                            VersionSource wikiSource, ModuleFactBundle bundle) {
        Path versionRoot = resolveBelow(draftRoot, projectId, version);
        Path target = resolveBelow(draftRoot, projectId, version, buildId);
        Path staging = versionRoot.resolve("." + buildId + ".next").normalize();
        if (!staging.startsWith(versionRoot)) throw new IllegalArgumentException("unsafe draft path");
        try {
            Files.createDirectories(versionRoot);
            deleteRecursively(staging);
            Files.createDirectories(staging);
            writeSafeJson(staging.resolve("wiki-source.json"), wikiSource);
            writeSafeJson(staging.resolve("module-bundle.json"), bundle);
            move(staging, target);
            return target;
        } catch (IOException exception) {
            deleteQuietly(staging);
            throw new IllegalStateException("模块知识草稿写入失败", exception);
        }
    }

    /** 序列化对象为 JSON 并校验不含向量、Qdrant 运行数据或凭据等敏感字段后写入文件。 */
    private void writeSafeJson(Path file, Object value) throws IOException {
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)
                + System.lineSeparator();
        if (FORBIDDEN_FIELD.matcher(json).find()) {
            throw new IllegalArgumentException("模块草稿不得包含向量、Qdrant 运行数据或凭据字段");
        }
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private void move(Path source, Path target) throws IOException {
        if (Files.exists(target)) throw new IllegalStateException("模块知识草稿目录已存在");
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            log.debug("Atomic move is unsupported for the draft filesystem; using a regular move", exception);
            Files.move(source, target);
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private void deleteQuietly(Path root) {
        try {
            deleteRecursively(root);
        } catch (IOException exception) {
            log.warn("Best-effort module draft cleanup failed; path is omitted from logs", exception);
        }
    }

    private Path resolveBelow(Path root, String... parts) {
        Path result = root;
        for (String part : parts) result = result.resolve(identifier(part, "path"));
        result = result.normalize();
        if (!result.startsWith(root)) throw new IllegalArgumentException("unsafe draft path");
        return result;
    }

    private String identifier(String value, String field) {
        String normalized = text(value).trim();
        if (!SAFE_IDENTIFIER.matcher(normalized).matches() || normalized.contains("..")) {
            throw new IllegalArgumentException(field + " contains unsafe characters");
        }
        return normalized;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
