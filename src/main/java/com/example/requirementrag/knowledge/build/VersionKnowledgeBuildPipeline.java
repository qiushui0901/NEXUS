package com.example.requirementrag.knowledge.build;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.config.WikiProperties;
import com.example.requirementrag.knowledge.build.KnowledgeBuildModels.BuildArtifact;
import com.example.requirementrag.knowledge.build.KnowledgeBuildModels.BuildRequest;
import com.example.requirementrag.knowledge.build.KnowledgeBuildModels.BuildResult;
import com.example.requirementrag.knowledge.build.KnowledgeBuildModels.BuildStatus;
import com.example.requirementrag.knowledge.build.KnowledgeBuildModels.FeatureFactDraft;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalPipeline;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import com.example.requirementrag.service.RagUnavailableException;
import com.example.requirementrag.wiki.WikiModels.CodeEntry;
import com.example.requirementrag.wiki.WikiModels.Evidence;
import com.example.requirementrag.wiki.WikiModels.KnowledgeQuality;
import com.example.requirementrag.wiki.WikiModels.PageSource;
import com.example.requirementrag.wiki.WikiModels.RequirementSource;
import com.example.requirementrag.wiki.WikiModels.Status;
import com.example.requirementrag.wiki.WikiModels.TestKnowledge;
import com.example.requirementrag.wiki.WikiModels.VersionChange;
import com.example.requirementrag.wiki.WikiModels.VersionSource;
import com.example.requirementrag.versioning.RequirementChunkDiff;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Builds versioned knowledge drafts from requirement-version deltas without publishing them. */
@Service
public class VersionKnowledgeBuildPipeline {
    private static final int MAX_FEATURES = 100;
    private static final int MAX_REQUIREMENT_EVIDENCE = 5;
    private static final int MAX_CODE_EVIDENCE = 8;
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern FORBIDDEN_FIELD = Pattern.compile(
            "(?i)\\\"(?:vector|vectors|denseVector|sparseVector|embedding|embeddings|qdrantPoint|qdrantPoints|"
                    + "snapshot|snapshots|storage|apiKey|password|secret|token|authorization|credential|credentials)"
                    + "\\\"\\s*:");

    private final ObjectMapper objectMapper;
    private final ProjectRegistry projectRegistry;
    private final QdrantHybridStore documentStore;
    private final RetrievalPipeline retrievalPipeline;
    private final Path draftRoot;

    public VersionKnowledgeBuildPipeline(ObjectMapper objectMapper, WikiProperties wikiProperties,
                                         ProjectRegistry projectRegistry, QdrantHybridStore documentStore,
                                         RetrievalPipeline retrievalPipeline) {
        this.objectMapper = objectMapper;
        this.projectRegistry = projectRegistry;
        this.documentStore = documentStore;
        this.retrievalPipeline = retrievalPipeline;
        this.draftRoot = Path.of(wikiProperties.draftPath()).toAbsolutePath().normalize();
    }

    public BuildResult build(BuildRequest request) {
        String projectId = identifier(request.projectId(), "projectId");
        String version = identifier(request.version(), "version");
        String documentId = identifier(request.documentId(), "documentId");
        String baseVersion = hasText(request.baseVersion()) ? identifier(request.baseVersion(), "baseVersion") : "";
        RagProperties.ProjectConfig project = projectRegistry.require(projectId);
        String collection = projectRegistry.resolveRequirementCollection(projectId);

        List<ChunkRecord> current = readVersion(collection, documentId, version);
        List<ChunkRecord> baseline = baseVersion.isBlank()
                ? List.of()
                : readVersion(collection, documentId, baseVersion);
        List<Candidate> candidates = candidates(current, baseline, baseVersion.isBlank());
        List<RagWarning> warnings = new ArrayList<>();
        if (candidates.size() > MAX_FEATURES) {
            warnings.add(new RagWarning("knowledge.build", "FEATURE_LIMIT_APPLIED",
                    "变化功能过多，草稿仅保留前 100 项", 0));
            candidates = candidates.stream().limit(MAX_FEATURES).toList();
        }

        Set<String> usedIds = new LinkedHashSet<>();
        List<FeatureFactDraft> features = new ArrayList<>();
        for (Candidate candidate : candidates) {
            features.add(toFeature(request, projectId, version, candidate, usedIds, warnings));
        }
        features.sort(Comparator.comparing(FeatureFactDraft::title).thenComparing(FeatureFactDraft::featureId));

        String generatedAt = Instant.now().toString();
        String buildId = generatedAt.replaceAll("[^0-9]", "").substring(0, 14)
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        BuildStatus status = features.isEmpty() ? BuildStatus.NO_CHANGES : BuildStatus.DRAFT;
        BuildArtifact artifact = new BuildArtifact(buildId, status, request, generatedAt,
                List.copyOf(features), uniqueWarnings(warnings));
        VersionSource wikiSource = toWikiSource(request, project, version, generatedAt, features);
        Path target = writeDraft(projectId, version, buildId, artifact, wikiSource);

        int conflicts = (int) features.stream().filter(feature -> !feature.conflicts().isEmpty()).count();
        int missingCode = (int) features.stream().filter(feature -> feature.codeEvidence().isEmpty()).count();
        int missingTests = (int) features.stream().filter(feature -> feature.testEvidence().isEmpty()).count();
        return new BuildResult(buildId, status, features.size(), conflicts, missingCode, missingTests,
                target.toString(), generatedAt, uniqueWarnings(warnings));
    }

    private FeatureFactDraft toFeature(BuildRequest request, String projectId, String version,
                                       Candidate candidate, Set<String> usedIds, List<RagWarning> warnings) {
        String title = title(candidate.filename());
        String featureId = uniqueFeatureId(title, candidate.filename(), usedIds);
        List<CodeChunk> code = List.of();
        try {
            RagOutcome<RetrievalBundle> outcome = retrievalPipeline.execute(new RetrievalRequest(
                    title + " " + excerpt(candidate.primaryText(), 180), RetrievalProfile.WIKI_BUILD,
                    projectId, request.documentId(), version, MAX_CODE_EVIDENCE));
            code = outcome.data().codeEvidence();
            warnings.addAll(outcome.warnings());
        } catch (RagUnavailableException unavailable) {
            warnings.addAll(unavailable.warnings());
        }

        List<Evidence> requirementEvidence = candidate.evidence().stream()
                .limit(MAX_REQUIREMENT_EVIDENCE)
                .map(item -> requirementEvidence(item.chunk(), item.version()))
                .toList();
        List<Evidence> codeEvidence = code.stream().limit(MAX_CODE_EVIDENCE)
                .map(item -> codeEvidence(item, request.codeCommit(), version, projectId))
                .toList();
        List<String> productRules = candidate.evidence().stream()
                .map(VersionedChunk::chunk)
                .map(ChunkRecord::parentText)
                .filter(this::hasText)
                .map(text -> "待审核摘录：" + excerpt(text, 240))
                .distinct()
                .limit(4)
                .toList();
        List<String> codeSymbols = code.stream()
                .map(item -> item.symbolType() + " " + item.symbolName() + " @ " + item.filePath())
                .distinct().limit(MAX_CODE_EVIDENCE).toList();
        List<String> testPoints = List.of(
                "核验“" + title + "”在 " + version + " 的新增、修改或删除边界。",
                "根据人工确认的产品规则补充正常、重复操作、配置缺失和版本回归测试。",
                "测试完成前保持 PENDING_REVIEW，不得标记为已验证。"
        );
        List<String> conflicts = featureConflicts(title);
        double confidence = Math.min(0.9, 0.35 + Math.min(requirementEvidence.size(), 3) * 0.1
                + (codeEvidence.isEmpty() ? 0.0 : 0.25));
        return new FeatureFactDraft(featureId, title, candidate.changeType(), productRules, codeSymbols,
                testPoints, requirementEvidence, codeEvidence, List.of(), conflicts, confidence,
                "PENDING_REVIEW");
    }

    private VersionSource toWikiSource(BuildRequest request, RagProperties.ProjectConfig project, String version,
                                       String generatedAt, List<FeatureFactDraft> features) {
        String projectName = hasText(project.name()) ? project.name() : project.id();
        List<PageSource> pages = features.stream().map(feature -> {
            List<RequirementSource> requirementSources = feature.requirementEvidence().stream()
                    .map(item -> new RequirementSource(request.documentId(), feature.featureId(),
                            item.source(), item.version(), item.location(), "", item.verificationStatus()))
                    .toList();
            List<CodeEntry> codeEntries = feature.codeEvidence().stream()
                    .map(item -> new CodeEntry("待审核代码入口", item.filePath(), item.symbol(), item.commit(),
                            feature.changeType(), item.verificationStatus()))
                    .toList();
            List<String> missing = new ArrayList<>();
            if (requirementSources.isEmpty()) missing.add("需求证据");
            if (codeEntries.isEmpty()) missing.add("代码证据");
            missing.add("真实测试执行快照");
            return new PageSource(
                    feature.featureId(), feature.title(), "自动草稿", version, Status.DRAFT,
                    List.of(), "由版本需求增量生成的待审核知识页；所有结论均需依据右侧证据核验。",
                    requirementSources, feature.productRules(), List.of(), codeEntries, feature.codeSymbols(),
                    List.of(), feature.conflicts(), feature.testPoints(), feature.testPoints(),
                    new TestKnowledge("NOT_AVAILABLE", "", "没有真实执行快照", List.of()),
                    new VersionChange(feature.changeType(), safe(request.baseVersion()), version,
                            "基于需求版本增量识别为 " + feature.changeType()),
                    new KnowledgeQuality("PENDING_REVIEW", requirementSources.size(), codeEntries.size(),
                            false, List.copyOf(missing)),
                    feature.conflicts(), List.of(), concat(feature.requirementEvidence(), feature.codeEvidence())
            );
        }).toList();
        return new VersionSource(2, project.id(), projectName, version, version,
                safe(request.baseCodeCommit()), safe(request.codeCommit()), generatedAt, pages);
    }

    private Path writeDraft(String projectId, String version, String buildId,
                            BuildArtifact artifact, VersionSource wikiSource) {
        Path versionRoot = resolveBelow(draftRoot, projectId, version);
        Path target = resolveBelow(draftRoot, projectId, version, buildId);
        Path staging = versionRoot.resolve("." + buildId + ".next").normalize();
        if (!staging.startsWith(versionRoot)) {
            throw new IllegalArgumentException("unsafe draft path");
        }
        try {
            Files.createDirectories(versionRoot);
            deleteRecursively(staging);
            Files.createDirectories(staging);
            writeSafeJson(staging.resolve("build.json"), artifact);
            writeSafeJson(staging.resolve("wiki-source.json"), wikiSource);
            move(staging, target);
            return target;
        } catch (IOException exception) {
            deleteQuietly(staging);
            throw new IllegalStateException("知识草稿写入失败");
        }
    }

    private void writeSafeJson(Path file, Object value) throws IOException {
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + System.lineSeparator();
        if (FORBIDDEN_FIELD.matcher(json).find()) {
            throw new IllegalArgumentException("知识草稿不得包含向量、Qdrant 运行数据或凭据字段");
        }
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private void move(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            throw new IllegalStateException("知识草稿目录已存在");
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private List<Candidate> candidates(List<ChunkRecord> current, List<ChunkRecord> baseline, boolean fullVersion) {
        if (fullVersion) {
            return groupCandidates(current.stream().map(chunk -> new VersionedChunk(chunk, chunk.version())).toList(),
                    List.of(), "CURRENT_VERSION");
        }
        List<RequirementChunkDiff.ParentChange> diff = RequirementChunkDiff.compare(baseline, current);
        List<VersionedChunk> changed = diff.stream()
                .filter(change -> change.after() != null)
                .map(change -> new VersionedChunk(change.after(), change.after().version())).toList();
        List<VersionedChunk> removed = diff.stream()
                .filter(change -> change.before() != null && change.type() != RequirementChunkDiff.Type.ADDED)
                .map(change -> new VersionedChunk(change.before(), change.before().version())).toList();
        return groupCandidates(changed, removed, null);
    }

    private List<Candidate> groupCandidates(List<VersionedChunk> changed, List<VersionedChunk> removed,
                                            String fixedChangeType) {
        Map<String, CandidateParts> grouped = new LinkedHashMap<>();
        changed.forEach(chunk -> grouped.computeIfAbsent(filename(chunk.chunk()), ignored -> new CandidateParts())
                .changed.add(chunk));
        removed.forEach(chunk -> grouped.computeIfAbsent(filename(chunk.chunk()), ignored -> new CandidateParts())
                .removed.add(chunk));
        return grouped.entrySet().stream().map(entry -> {
            CandidateParts parts = entry.getValue();
            String changeType = fixedChangeType != null ? fixedChangeType
                    : !parts.changed.isEmpty() && !parts.removed.isEmpty() ? "MODIFIED"
                    : !parts.changed.isEmpty() ? "ADDED_OR_CHANGED" : "REMOVED";
            List<VersionedChunk> evidence = new ArrayList<>(parts.changed);
            evidence.addAll(parts.removed);
            evidence.sort(Comparator.comparingInt(item -> item.chunk().parentOrder()));
            return new Candidate(entry.getKey(), changeType, List.copyOf(evidence));
        }).sorted(Comparator.comparing(Candidate::filename)).toList();
    }

    private List<ChunkRecord> readVersion(String collection, String documentId, String version) {
        try {
            return deduplicateParents(documentStore.scrollVersion(collection, documentId, version));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("需求版本数据读取失败");
        }
    }

    private List<ChunkRecord> deduplicateParents(List<ChunkRecord> chunks) {
        return RequirementChunkDiff.deduplicate(chunks);
    }

    private Evidence requirementEvidence(ChunkRecord chunk, String evidenceVersion) {
        return new Evidence("REQUIREMENT", filename(chunk), filename(chunk), evidenceVersion,
                "parentOrder=" + chunk.parentOrder(), excerpt(chunk.parentText(), 360), "", "", "",
                "PENDING_REVIEW");
    }

    private Evidence codeEvidence(CodeChunk chunk, String commit, String version, String projectId) {
        return new Evidence("CODE", safe(chunk.symbolName()), projectId, version,
                "lines=" + chunk.startLine() + '-' + chunk.endLine(), excerpt(chunk.text(), 360), safe(commit),
                safe(chunk.filePath()), safe(chunk.symbolName()), "PENDING_REVIEW");
    }

    private List<String> featureConflicts(String title) {
        return List.of();
    }

    private String uniqueFeatureId(String title, String filename, Set<String> usedIds) {
        String base = title.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isBlank()) base = "feature-" + sha256(filename).substring(0, 12);
        if (base.length() > 60) base = base.substring(0, 60).replaceAll("-$", "");
        String candidate = base;
        if (usedIds.contains(candidate)) candidate = base + '-' + sha256(filename).substring(0, 8);
        int suffix = 2;
        while (!usedIds.add(candidate)) candidate = base + '-' + suffix++;
        return candidate;
    }

    private String title(String filename) {
        String normalized = filename(filename);
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String filename(ChunkRecord chunk) {
        return hasText(chunk.filename()) ? chunk.filename().replace('\\', '/') : "unknown-requirement";
    }

    private String filename(String value) {
        return hasText(value) ? value.replace('\\', '/') : "unknown-requirement";
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(safe(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
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
        String normalized = safe(value).trim();
        if (!SAFE_IDENTIFIER.matcher(normalized).matches() || normalized.contains("..")) {
            throw new IllegalArgumentException(field + " contains unsafe characters");
        }
        return normalized;
    }

    private String excerpt(String value, int maxChars) {
        String normalized = safe(value).replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "…";
    }

    private List<RagWarning> uniqueWarnings(List<RagWarning> warnings) {
        Map<String, RagWarning> unique = new LinkedHashMap<>();
        for (RagWarning warning : warnings) {
            unique.putIfAbsent(warning.stage() + ':' + warning.code(), warning);
        }
        return List.copyOf(unique.values());
    }

    private List<Evidence> concat(List<Evidence> first, List<Evidence> second) {
        List<Evidence> result = new ArrayList<>(first);
        result.addAll(second);
        return List.copyOf(result);
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
        } catch (IOException ignored) {
            // Best-effort cleanup; public failures never include internal paths or exception details.
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record VersionedChunk(ChunkRecord chunk, String version) {}

    private record Candidate(String filename, String changeType, List<VersionedChunk> evidence) {
        private String primaryText() {
            return evidence.isEmpty() ? filename : evidence.getFirst().chunk().parentText();
        }
    }

    private static final class CandidateParts {
        private final List<VersionedChunk> changed = new ArrayList<>();
        private final List<VersionedChunk> removed = new ArrayList<>();
    }
}
