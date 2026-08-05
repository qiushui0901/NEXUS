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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * 版本知识构建流水线：基于需求版本增量差异（新增/修改/删除）生成可审核的知识草稿，
 * 不直接发布；产物以 build.json 与 wiki-source.json 原子落盘，并初始化草稿状态机。
 */
@Service
public class VersionKnowledgeBuildPipeline {
    private static final Logger log = LoggerFactory.getLogger(VersionKnowledgeBuildPipeline.class);
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
    private final KnowledgeDraftLifecycleService draftLifecycleService;

    /** 注入 JSON 序列化器、项目注册表、向量存储、检索流水线与草稿生命周期服务。 */
    @Autowired
    public VersionKnowledgeBuildPipeline(ObjectMapper objectMapper, WikiProperties wikiProperties,
                                         ProjectRegistry projectRegistry, QdrantHybridStore documentStore,
                                         RetrievalPipeline retrievalPipeline,
                                         KnowledgeDraftLifecycleService draftLifecycleService) {
        this.objectMapper = objectMapper;
        this.projectRegistry = projectRegistry;
        this.documentStore = documentStore;
        this.retrievalPipeline = retrievalPipeline;
        this.draftRoot = Path.of(wikiProperties.draftPath()).toAbsolutePath().normalize();
        this.draftLifecycleService = draftLifecycleService;
    }

    /** 兼容直接测试/嵌入式构造的简化构造器（不携带草稿生命周期服务，构建后不初始化草稿）。 */
    public VersionKnowledgeBuildPipeline(ObjectMapper objectMapper, WikiProperties wikiProperties,
                                         ProjectRegistry projectRegistry, QdrantHybridStore documentStore,
                                         RetrievalPipeline retrievalPipeline) {
        this(objectMapper, wikiProperties, projectRegistry, documentStore, retrievalPipeline, null);
    }

    /** 以 “system” 为操作人执行一次构建。 */
    public BuildResult build(BuildRequest request) {
        return build(request, "system");
    }

    /** 执行构建主流程：读取当前/基线版本块 → 生成变化候选 → 组装功能草稿 → 落盘产物与 Wiki 源 → 初始化草稿元数据。 */
    public BuildResult build(BuildRequest request, String actor) {
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
        if (draftLifecycleService != null) {
            try {
                draftLifecycleService.initializeDraft(projectId, version, buildId, actor, generatedAt);
            } catch (RuntimeException exception) {
                deleteQuietly(target);
                throw exception;
            }
        }

        int conflicts = (int) features.stream().filter(feature -> !feature.conflicts().isEmpty()).count();
        int missingCode = (int) features.stream().filter(feature -> feature.codeEvidence().isEmpty()).count();
        int missingTests = (int) features.stream().filter(feature -> feature.testEvidence().isEmpty()).count();
        return new BuildResult(buildId, status, features.size(), conflicts, missingCode, missingTests,
                target.toString(), generatedAt, uniqueWarnings(warnings));
    }

    /** 将单个变化候选转换为功能事实草稿：检索代码证据、汇总产品规则与测试要点，并计算置信度。 */
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

    /** 将功能草稿列表转换为 Wiki 页面源（VersionSource），统一标注待审核状态与缺失证据。 */
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

    /** 以暂存目录方式原子落盘 build.json 与 wiki-source.json，整体移入目标草稿目录。 */
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

    /** 序列化对象为 JSON 并校验不含向量、Qdrant 运行数据或凭据等敏感字段后写入文件。 */
    private void writeSafeJson(Path file, Object value) throws IOException {
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + System.lineSeparator();
        if (FORBIDDEN_FIELD.matcher(json).find()) {
            throw new IllegalArgumentException("知识草稿不得包含向量、Qdrant 运行数据或凭据字段");
        }
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    /** 原子移动暂存目录到草稿目录；目标已存在则报错，文件系统不支持原子移动时回退普通移动。 */
    private void move(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            throw new IllegalStateException("知识草稿目录已存在");
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            log.debug("Atomic move is unsupported for the draft filesystem; using a regular move", exception);
            Files.move(source, target);
        }
    }

    /** 生成变化候选：全量构建取全部条目；增量构建对比基线，分离出新增/修改与删除条目。 */
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

    /** 按文件名分组变化/删除条目为候选：推断变化类型（MODIFIED / ADDED_OR_CHANGED / REMOVED），证据按 parentOrder 排序。 */
    private List<Candidate> groupCandidates(List<VersionedChunk> changed, List<VersionedChunk> removed,
                                            String fixedChangeType) {
        Map<String, CandidateParts> grouped = new LinkedHashMap<>();
        changed.forEach(chunk -> grouped.computeIfAbsent(filename(chunk.chunk()), key -> new CandidateParts())
                .changed.add(chunk));
        removed.forEach(chunk -> grouped.computeIfAbsent(filename(chunk.chunk()), key -> new CandidateParts())
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

    /** 滚动读取某文档指定版本的全部块数据并去重父级，失败时抛出统一异常。 */
    private List<ChunkRecord> readVersion(String collection, String documentId, String version) {
        try {
            return deduplicateParents(documentStore.scrollVersion(collection, documentId, version));
        } catch (RuntimeException exception) {
            log.error("Requirement version data read failed for document {} version {}", documentId, version,
                    exception);
            throw new IllegalStateException("需求版本数据读取失败", exception);
        }
    }

    /** 去重父级块（委托给 RequirementChunkDiff）。 */
    private List<ChunkRecord> deduplicateParents(List<ChunkRecord> chunks) {
        return RequirementChunkDiff.deduplicate(chunks);
    }

    /** 构造需求证据条目：来源为需求块文件名，位置标注 parentOrder，待审核状态。 */
    private Evidence requirementEvidence(ChunkRecord chunk, String evidenceVersion) {
        return new Evidence("REQUIREMENT", filename(chunk), filename(chunk), evidenceVersion,
                "parentOrder=" + chunk.parentOrder(), excerpt(chunk.parentText(), 360), "", "", "",
                "PENDING_REVIEW");
    }

    /** 构造代码证据条目：标注行号区间与提交、文件、符号信息，待审核状态。 */
    private Evidence codeEvidence(CodeChunk chunk, String commit, String version, String projectId) {
        return new Evidence("CODE", safe(chunk.symbolName()), projectId, version,
                "lines=" + chunk.startLine() + '-' + chunk.endLine(), excerpt(chunk.text(), 360), safe(commit),
                safe(chunk.filePath()), safe(chunk.symbolName()), "PENDING_REVIEW");
    }

    /** 冲突检测：当前阶段不自动生成冲突，返回空列表交由人工审核补充。 */
    private List<String> featureConflicts(String title) {
        return List.of();
    }

    /** 由标题生成唯一功能 ID：转 kebab-case，冲突时依次追加文件哈希后缀与数字后缀。 */
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

    /** 从文件名提取标题：去掉目录前缀与扩展名。 */
    private String title(String filename) {
        String normalized = filename(filename);
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 规范化块的文件名（反斜杠转正斜杠），缺失时用占位名。 */
    private String filename(ChunkRecord chunk) {
        return hasText(chunk.filename()) ? chunk.filename().replace('\\', '/') : "unknown-requirement";
    }

    /** 规范化文件名字符串（反斜杠转正斜杠），缺失时用占位名。 */
    private String filename(String value) {
        return hasText(value) ? value.replace('\\', '/') : "unknown-requirement";
    }

    /** 计算字符串的 SHA-256 十六进制摘要。 */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(safe(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    /** 在根目录下拼接多级标识符路径并归一化，校验结果不越出根目录。 */
    private Path resolveBelow(Path root, String... parts) {
        Path result = root;
        for (String part : parts) result = result.resolve(identifier(part, "path"));
        result = result.normalize();
        if (!result.startsWith(root)) throw new IllegalArgumentException("unsafe draft path");
        return result;
    }

    /** 校验并规范化标识符：拒绝含不安全字符或路径穿越（..）的值。 */
    private String identifier(String value, String field) {
        String normalized = safe(value).trim();
        if (!SAFE_IDENTIFIER.matcher(normalized).matches() || normalized.contains("..")) {
            throw new IllegalArgumentException(field + " contains unsafe characters");
        }
        return normalized;
    }

    /** 折叠连续空白并截断文本到指定最大字符数（超长以省略号结尾）。 */
    private String excerpt(String value, int maxChars) {
        String normalized = safe(value).replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "…";
    }

    /** 按 “阶段+代码” 键去重警告列表。 */
    private List<RagWarning> uniqueWarnings(List<RagWarning> warnings) {
        Map<String, RagWarning> unique = new LinkedHashMap<>();
        for (RagWarning warning : warnings) {
            unique.putIfAbsent(warning.stage() + ':' + warning.code(), warning);
        }
        return List.copyOf(unique.values());
    }

    /** 拼接两段证据列表为不可变副本。 */
    private List<Evidence> concat(List<Evidence> first, List<Evidence> second) {
        List<Evidence> result = new ArrayList<>(first);
        result.addAll(second);
        return List.copyOf(result);
    }

    /** 递归删除目录：先删子项再删目录本身。 */
    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    /** 尽力而为的静默删除：失败仅记录警告，不抛出。 */
    private void deleteQuietly(Path root) {
        try {
            deleteRecursively(root);
        } catch (IOException exception) {
            log.warn("Best-effort draft cleanup failed; path is omitted from logs", exception);
        }
    }

    /** 判断字符串非空（非 null 且不含空白）。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** null 安全取值：空引用返回空字符串。 */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    /** 带版本标注的需求块。 */
    private record VersionedChunk(ChunkRecord chunk, String version) {}

    /** 构建候选：文件名、变化类型与证据块列表。 */
    private record Candidate(String filename, String changeType, List<VersionedChunk> evidence) {
        /** 候选主文本：取首条证据的父文本，无证据时退回文件名。 */
        private String primaryText() {
            return evidence.isEmpty() ? filename : evidence.getFirst().chunk().parentText();
        }
    }

    /** 按文件分组时的聚合容器：收集新增/修改块与删除块。 */
    private static final class CandidateParts {
        private final List<VersionedChunk> changed = new ArrayList<>();
        private final List<VersionedChunk> removed = new ArrayList<>();
    }
}
