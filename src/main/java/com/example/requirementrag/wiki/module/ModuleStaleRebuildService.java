package com.example.requirementrag.wiki.module;

import com.example.requirementrag.config.WikiProperties;
import com.example.requirementrag.knowledge.build.KnowledgeDraftLifecycleService;
import com.example.requirementrag.knowledge.build.KnowledgeDraftModels.DraftMetadata;
import com.example.requirementrag.wiki.WikiModels.Claim;
import com.example.requirementrag.wiki.WikiModels.Page;
import com.example.requirementrag.wiki.WikiModels.PageSource;
import com.example.requirementrag.wiki.WikiModels.VersionSource;
import com.example.requirementrag.wiki.WikiRepository;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 过期到草稿重建：读取已发布模块页 → 重建事实包 → 对比 Claims → 生成带差异的待审核草稿。 */
@Service
public class ModuleStaleRebuildService {
    private static final Logger log = LoggerFactory.getLogger(ModuleStaleRebuildService.class);
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern FORBIDDEN_FIELD = Pattern.compile(
            "(?i)\\\"(?:vector|vectors|denseVector|sparseVector|embedding|embeddings|qdrantPoint|qdrantPoints|"
                    + "snapshot|snapshots|storage|apiKey|password|secret|token|authorization|credential|credentials)"
                    + "\\\"\\s*:");

    /** 单条 Claim 的变化：新增、内容或支持状态变化、删除或未变化。 */
    public record ClaimChange(String claimId, String changeType, String section, String oldText, String newText) {}

    /** 重建结果：草稿元数据与 Claim 级差异。 */
    public record RebuildResult(DraftMetadata draft, List<ClaimChange> claimChanges) {}

    private final ObjectMapper objectMapper;
    private final WikiRepository wikiRepository;
    private final ModuleFactExtractor extractor;
    private final ModuleWikiPlanner planner;
    private final ModuleClaimQualityGate qualityGate;
    private final KnowledgeDraftLifecycleService draftLifecycleService;
    private final Path draftRoot;

    public ModuleStaleRebuildService(ObjectMapper objectMapper, WikiProperties properties,
                                     WikiRepository wikiRepository, ModuleFactExtractor extractor,
                                     ModuleWikiPlanner planner, ModuleClaimQualityGate qualityGate,
                                     KnowledgeDraftLifecycleService draftLifecycleService) {
        this.objectMapper = objectMapper;
        this.wikiRepository = wikiRepository;
        this.extractor = extractor;
        this.planner = planner;
        this.qualityGate = qualityGate;
        this.draftLifecycleService = draftLifecycleService;
        this.draftRoot = Path.of(properties.draftPath()).toAbsolutePath().normalize();
    }

    /** 重建已发布模块页：抽取 → 规划 → 质量门 → 写草稿（含 Claim 差异）→ 初始化审核。 */
    public RebuildResult rebuild(String projectId, String version, String modulePath, String featureId,
                                 String codeCommit, String actor) {
        String safeProject = identifier(projectId, "projectId");
        String safeVersion = identifier(version, "version");
        Page published = wikiRepository.getPage(safeProject, safeVersion, identifier(featureId, "featureId"));
        List<Claim> oldClaims = new ArrayList<>(published.claims());

        ModuleFactBundle bundle = extractor.extract(safeProject, modulePath, safeVersion);
        PageSource page = planner.plan(bundle, safeVersion, null, codeCommit);
        String targetCommit = codeCommit == null || codeCommit.isBlank()
                ? text(bundle.commitSha()) : codeCommit;
        qualityGate.validate(safeProject, safeVersion, targetCommit, List.of(page));

        List<ClaimChange> changes = claimChanges(oldClaims, page.claims());
        String buildId = Instant.now().toString().replaceAll("[^0-9]", "").substring(0, 14)
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        String generatedAt = Instant.now().toString();
        VersionSource wikiSource = new VersionSource(2, safeProject, safeProject, safeVersion, safeVersion,
                published.codeCommit(), codeCommit == null ? text(bundle.commitSha()) : codeCommit,
                generatedAt, List.of(page));
        Path target = writeDraft(safeProject, safeVersion, buildId, wikiSource, bundle, changes);
        DraftMetadata draft = draftLifecycleService.initializeDraft(safeProject, safeVersion, buildId,
                text(actor).isBlank() ? "system" : actor, generatedAt);
        return new RebuildResult(draft, List.copyOf(changes));
    }

    /** 对比新旧 Claims：按 claimId 对齐，输出新增、修改（文本或支持状态变化）、删除与未变化。 */
    private List<ClaimChange> claimChanges(List<Claim> oldClaims, List<Claim> newClaims) {
        List<ClaimChange> changes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Claim claim : newClaims) {
            seen.add(claim.claimId());
            Claim previous = oldClaims.stream().filter(old -> old.claimId().equals(claim.claimId()))
                    .findFirst().orElse(null);
            if (previous == null) {
                changes.add(new ClaimChange(claim.claimId(), "ADDED", claim.section(), "", text(claim.text())));
            } else if (!previous.text().equals(claim.text()) || previous.support() != claim.support()
                    || !previous.evidenceIds().equals(claim.evidenceIds())) {
                changes.add(new ClaimChange(claim.claimId(), "MODIFIED", claim.section(),
                        text(previous.text()), text(claim.text())));
            } else {
                changes.add(new ClaimChange(claim.claimId(), "UNCHANGED", claim.section(), text(claim.text()),
                        text(claim.text())));
            }
        }
        for (Claim old : oldClaims) {
            if (!seen.contains(old.claimId())) {
                changes.add(new ClaimChange(old.claimId(), "REMOVED", old.section(), text(old.text()), ""));
            }
        }
        return changes;
    }

    private Path writeDraft(String projectId, String version, String buildId,
                            VersionSource wikiSource, ModuleFactBundle bundle, List<ClaimChange> changes) {
        Path versionRoot = resolveBelow(draftRoot, projectId, version);
        Path target = resolveBelow(draftRoot, projectId, version, buildId);
        Path staging = versionRoot.resolve("." + buildId + ".next").normalize();
        if (!staging.startsWith(versionRoot)) throw new IllegalArgumentException("unsafe draft path");
        try {
            Files.createDirectories(versionRoot);
            deleteRecursively(staging);
            Files.createDirectories(staging);
            writeSafeJson(staging.resolve("build.json"), buildArtifact(projectId, version, buildId));
            writeSafeJson(staging.resolve("wiki-source.json"), wikiSource);
            writeSafeJson(staging.resolve("module-bundle.json"), bundle);
            writeSafeJson(staging.resolve("claim-diff.json"), Map.of("claimChanges", changes));
            move(staging, target);
            return target;
        } catch (IOException exception) {
            deleteQuietly(staging);
            throw new IllegalStateException("模块重建草稿写入失败", exception);
        }
    }

    /** 合成与现有发布链路兼容的构建产物。 */
    private com.example.requirementrag.knowledge.build.KnowledgeBuildModels.BuildArtifact buildArtifact(
            String projectId, String version, String buildId) {
        return new com.example.requirementrag.knowledge.build.KnowledgeBuildModels.BuildArtifact(
                buildId, com.example.requirementrag.knowledge.build.KnowledgeBuildModels.BuildStatus.DRAFT,
                new com.example.requirementrag.knowledge.build.KnowledgeBuildModels.BuildRequest(
                        projectId, version, null, projectId, "", ""),
                java.time.Instant.now().toString(), List.of(), List.of());
    }

    private void writeSafeJson(Path file, Object value) throws IOException {
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)
                + System.lineSeparator();
        if (FORBIDDEN_FIELD.matcher(json).find()) {
            throw new IllegalArgumentException("模块重建草稿不得包含向量、Qdrant 运行数据或凭据字段");
        }
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private void move(Path source, Path target) throws IOException {
        if (Files.exists(target)) throw new IllegalStateException("模块重建草稿目录已存在");
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
            log.warn("Best-effort module rebuild cleanup failed; path is omitted from logs", exception);
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
