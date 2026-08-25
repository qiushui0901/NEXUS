package com.example.requirementrag.requirement.semantic;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.project.BusinessProjectCatalogService;
import com.example.requirementrag.requirement.graph.RequirementGraphWindowPlanner;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.ChunkFailure;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationInput;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationOutcome;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationRecord;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildRecord;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildRequest;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildResult;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildStatus;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.ClaimStatus;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.ExtractionStatus;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticErrorCode;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 语义标注构建编排：加载父块 → 计算输入哈希 → 跳过未变化内容 → 批量 LLM 标注 →
 * 幂等持久化。预算（模型调用 / 墙钟 / Token）内可恢复，部分失败不被伪装成完整成功。
 */
@Service
@ConditionalOnProperty(prefix = "app.rag.requirement-semantic", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class RequirementSemanticBuildService {
    private static final Logger log = LoggerFactory.getLogger(RequirementSemanticBuildService.class);
    private static final int MAX_FAILURES_IN_RESULT = 50;

    private final SQLiteRequirementSemanticStore store;
    private final RequirementSemanticAnnotationService annotationService;
    private final QdrantHybridStore qdrantStore;
    private final ProjectRegistry projectRegistry;
    private final BusinessProjectCatalogService businessProjects;
    private final RequirementSemanticProperties properties;
    private final RequirementGraphWindowPlanner windowPlanner;
    private final RequirementSemanticTextComposer composer;
    private final MeterRegistry meterRegistry;

    @Autowired
    public RequirementSemanticBuildService(SQLiteRequirementSemanticStore store,
                                           RequirementSemanticAnnotationService annotationService,
                                           QdrantHybridStore qdrantStore,
                                           ProjectRegistry projectRegistry,
                                           ObjectProvider<BusinessProjectCatalogService> businessProjects,
                                           RequirementSemanticProperties properties,
                                           ObjectProvider<RequirementGraphWindowPlanner> windowPlanner,
                                           ObjectProvider<MeterRegistry> meterRegistry,
                                           RequirementSemanticTextComposer composer) {
        this(store, annotationService, qdrantStore, projectRegistry,
                businessProjects.getIfAvailable(), properties,
                windowPlanner.getIfAvailable(RequirementGraphWindowPlanner::new),
                meterRegistry.getIfAvailable(), composer);
    }

    /** Compatibility constructor for focused tests. */
    public RequirementSemanticBuildService(SQLiteRequirementSemanticStore store,
                                           RequirementSemanticAnnotationService annotationService,
                                           QdrantHybridStore qdrantStore,
                                           ProjectRegistry projectRegistry,
                                           RequirementSemanticProperties properties,
                                           RequirementSemanticTextComposer composer) {
        this(store, annotationService, qdrantStore, projectRegistry, null, properties,
                new RequirementGraphWindowPlanner(), null, composer);
    }

    private RequirementSemanticBuildService(SQLiteRequirementSemanticStore store,
                                            RequirementSemanticAnnotationService annotationService,
                                            QdrantHybridStore qdrantStore,
                                            ProjectRegistry projectRegistry,
                                            BusinessProjectCatalogService businessProjects,
                                            RequirementSemanticProperties properties,
                                            RequirementGraphWindowPlanner windowPlanner,
                                            MeterRegistry meterRegistry,
                                            RequirementSemanticTextComposer composer) {
        this.store = store;
        this.annotationService = annotationService;
        this.qdrantStore = qdrantStore;
        this.projectRegistry = projectRegistry;
        this.businessProjects = businessProjects;
        this.properties = properties;
        this.windowPlanner = windowPlanner == null ? new RequirementGraphWindowPlanner() : windowPlanner;
        this.meterRegistry = meterRegistry;
        this.composer = composer;
    }

    public SemanticBuildResult build(SemanticBuildRequest request) {
        validate(request);
        String projectId = resolveProjectId(request.projectId());
        count("nexus.requirement.semantic.started", projectId, "started");
        String collection = resolveCollection(projectId, request.collection());
        List<ChunkRecord> chunks = distinctParents(
                qdrantStore.scrollVersion(collection, request.documentId(), request.requirementVersion()));
        if (chunks.isEmpty()) {
            throw new RequirementSemanticException("SEMANTIC_INPUT_EMPTY", "需求版本没有可语义标注的父块");
        }
        // sourceRevision 必须对底层返回顺序不敏感：先按稳定键排序再哈希。
        List<ChunkRecord> orderedChunks = orderedBySourceKey(chunks);
        String sourceRevision = sourceRevision(orderedChunks);
        String model = annotationService.resolvedModel();
        String promptVersion = properties.promptVersion();
        String schemaVersion = properties.schemaVersion();
        boolean retryFailedOnly = Boolean.TRUE.equals(request.retryFailedOnly());

        List<SemanticAnnotationInput> inputs = annotationInputs(projectId, orderedChunks);
        int totalChunks = inputs.size();
        int skipped = 0;
        int completed = 0;
        int failed = 0;
        int modelCalls = 0;
        int tokenEstimate = 0;
        boolean budgetStopped = false;
        List<ChunkFailure> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Instant startedAt = Instant.now();
        Instant deadline = startedAt.plusSeconds(properties.maxWallClockSeconds());

        for (SemanticAnnotationInput input : inputs) {
            Optional<SemanticAnnotationRecord> existing = store.findExisting(
                    input.projectId(), input.documentId(), input.requirementVersion(),
                    input.sourceChunkId(), input.contentHash(), model, promptVersion, schemaVersion);
            if (existing.isPresent() && existing.get().extractionStatus() == ExtractionStatus.SUCCEEDED) {
                skipped++;
                count("nexus.requirement.semantic.completed", projectId, "skipped");
                continue;
            }
            if (retryFailedOnly && (existing.isEmpty()
                    || existing.get().extractionStatus() != ExtractionStatus.FAILED)) {
                skipped++;
                continue;
            }
            if (modelCalls >= properties.maxModelCalls()) {
                if (!warnings.contains(SemanticErrorCode.BUDGET_EXCEEDED.name())) {
                    warnings.add("SEMANTIC_BUDGET_MODEL_CALLS");
                }
                budgetStopped = true;
                break;
            }
            if (Instant.now().isAfter(deadline)) {
                warnings.add("SEMANTIC_BUDGET_WALL_CLOCK");
                budgetStopped = true;
                break;
            }
            if (properties.maxEstimatedTokens() > 0
                    && tokenEstimate + estimateInputTokens(input) > properties.maxEstimatedTokens()) {
                warnings.add("SEMANTIC_BUDGET_TOKENS");
                budgetStopped = true;
                break;
            }

            // 把剩余预算传给标注服务：单窗口内的重试也不会突破总预算。
            SemanticAnnotationOutcome outcome = annotationService.annotate(input,
                    properties.maxModelCalls() - modelCalls);
            modelCalls += outcome.modelCalls();
            tokenEstimate += outcome.tokenEstimate();
            int attempt = existing.map(record -> record.attemptCount() + 1).orElse(1);
            Instant createdAt = existing.map(SemanticAnnotationRecord::createdAt).orElse(null);
            SemanticAnnotationRecord record = record(projectId, sourceRevision, model, promptVersion,
                    schemaVersion, input, outcome, attempt, createdAt);
            store.save(record);

            // 结构化运行日志：只记录 ID、状态与预算，不落原文和模型输出（§18.1）。
            log.info("requirement semantic annotation project={} document={} version={} chunk={} window={} "
                            + "contentHash={} model={} promptVersion={} attempt={} latencyMs={} tokens={} status={} errorCode={}",
                    projectId, input.documentId(), input.requirementVersion(), input.sourceChunkId(),
                    input.windowId(), input.contentHash(), model, promptVersion, attempt,
                    outcome.latencyMs(), outcome.tokenEstimate(),
                    outcome.succeeded() ? "SUCCEEDED" : "FAILED",
                    outcome.succeeded() ? "-" : outcome.errorCode());
            if (outcome.succeeded()) {
                completed++;
                count("nexus.requirement.semantic.completed", projectId, "succeeded");
                timer("nexus.requirement.semantic.latency", projectId, outcome.latencyMs());
            } else {
                failed++;
                count("nexus.requirement.semantic.failed", projectId,
                        outcome.errorCode() == null ? "unknown" : outcome.errorCode().name().toLowerCase());
                if (failures.size() < MAX_FAILURES_IN_RESULT) {
                    failures.add(new ChunkFailure(input.sourceChunkId(), input.windowId(),
                            outcome.errorCode() == null ? "MODEL_UNAVAILABLE" : outcome.errorCode().name()));
                }
            }
        }

        // 预算中断或存在未处理输入时，不能把未完成构建伪装成 SUCCESS。
        int unprocessed = totalChunks - skipped - completed - failed;
        boolean incomplete = budgetStopped || unprocessed > 0;
        SemanticBuildStatus status;
        if (incomplete || failed > 0) {
            status = completed + skipped > 0 ? SemanticBuildStatus.PARTIAL_FAILURE : SemanticBuildStatus.FAILED;
        } else {
            status = SemanticBuildStatus.SUCCESS;
        }
        SemanticBuildResult result = new SemanticBuildResult(projectId, request.documentId(),
                request.requirementVersion(), sourceRevision, model, promptVersion, schemaVersion,
                totalChunks, skipped, completed, failed, status, warnings, failures);
        List<RequirementSemanticModels.SemanticBuildInput> buildInputs = inputs.stream()
                .map(input -> new RequirementSemanticModels.SemanticBuildInput(
                        input.sourceChunkId(), input.windowId(), input.contentHash()))
                .toList();
        persistBuildGeneration(projectId, request, sourceRevision, model, promptVersion, schemaVersion,
                result, startedAt, buildInputs);
        count("nexus.requirement.semantic.build", projectId, status.name().toLowerCase());
        return result;
    }

    /**
     * 持久化构建代际：只有 SUCCESS 构建才切换 active（PARTIAL_FAILURE/FAILED 不接管线上结果），
     * 同时持久化当前构建的输入集合，active 查询必须按输入集合过滤标注，
     * 不再批量改写旧 Annotation 的 source_revision。
     */
    private void persistBuildGeneration(String projectId, SemanticBuildRequest request,
                                        String sourceRevision, String model, String promptVersion,
                                        String schemaVersion, SemanticBuildResult result, Instant startedAt,
                                        List<RequirementSemanticModels.SemanticBuildInput> buildInputs) {
        boolean active = result.status() == SemanticBuildStatus.SUCCESS;
        String buildId = SQLiteRequirementSemanticStore.buildId(projectId, request.documentId(),
                request.requirementVersion(), sourceRevision, model, promptVersion, schemaVersion);
        store.saveBuild(new SemanticBuildRecord(buildId, projectId, request.documentId(),
                request.requirementVersion(), sourceRevision, model, promptVersion, schemaVersion,
                result.status(), result.totalChunks(), result.skippedChunks(), result.completedChunks(),
                result.failedChunks(), result.warnings(), startedAt, Instant.now(), active));
        store.saveBuildInputs(buildId, buildInputs);
    }

    /** 把父块展开为标注输入：短块整块标注，长块按结构感知窗口切分，不静默丢尾部。 */
    private List<SemanticAnnotationInput> annotationInputs(String projectId, List<ChunkRecord> chunks) {
        List<SemanticAnnotationInput> inputs = new ArrayList<>();
        for (ChunkRecord chunk : chunks) {
            String sourceChunkId = String.join("|", safe(chunk.filename()),
                    safe(chunk.parentId()), Integer.toString(chunk.parentOrder()));
            String parentText = chunk.parentText() == null ? "" : chunk.parentText();
            if (parentText.length() <= properties.maxInputChars()) {
                inputs.add(input(projectId, chunk, sourceChunkId, null, 0, 0, parentText.length(), parentText));
                continue;
            }
            var plan = windowPlanner.plan(chunk, properties.maxInputChars(),
                    RequirementGraphWindowPlanner.PlanOptions.legacy(properties.windowOverlapChars()));
            for (var window : plan.windows()) {
                inputs.add(input(projectId, chunk, sourceChunkId, window.id(), window.windowIndex(),
                        window.startOffset(), window.endOffset(), window.text()));
            }
        }
        return List.copyOf(inputs);
    }

    private SemanticAnnotationInput input(String projectId, ChunkRecord chunk, String sourceChunkId,
                                           String windowId, int windowIndex, int startOffset,
                                           int endOffset, String rawText) {
        return new SemanticAnnotationInput(
                projectId,
                chunk.documentId(),
                chunk.version(),
                sourceChunkId,
                chunk.parentId(),
                windowId,
                windowIndex,
                startOffset,
                endOffset,
                safe(chunk.filename()),
                chunk.parentOrder(),
                chunk.sectionPath(),
                chunk.heading(),
                rawText,
                sha256(String.join("|", sourceChunkId, safe(windowId), rawText)));
    }

    private SemanticAnnotationRecord record(String projectId, String sourceRevision, String model,
                                            String promptVersion, String schemaVersion,
                                            SemanticAnnotationInput input,
                                            SemanticAnnotationOutcome outcome, int attempt,
                                            Instant createdAt) {
        String annotationId = SQLiteRequirementSemanticStore.annotationId(projectId,
                input.documentId(), input.requirementVersion(), input.sourceChunkId(),
                input.contentHash(), model, promptVersion, schemaVersion);
        ExtractionStatus status = outcome.succeeded() ? ExtractionStatus.SUCCEEDED : ExtractionStatus.FAILED;
        return new SemanticAnnotationRecord(annotationId, projectId, input.documentId(),
                input.requirementVersion(), sourceRevision, input.sourceChunkId(), input.parentId(),
                input.windowId(), input.windowIndex(), input.startOffset(), input.endOffset(),
                input.sourceFile(), input.parentOrder(), input.contentHash(),
                input.rawText(),
                outcome.succeeded() ? composer.summary(outcome.annotation()) : null,
                outcome.succeeded() ? composer.compose(input.rawText(), outcome.annotation()) : null,
                outcome.succeeded() ? outcome.annotation() : null,
                model, promptVersion, schemaVersion, status,
                ClaimStatus.CANDIDATE, null, attempt, outcome.modelCalls(), outcome.latencyMs(),
                outcome.tokenEstimate(), outcome.succeeded() ? null : outcome.errorCode(),
                createdAt, Instant.now());
    }

    private void validate(SemanticBuildRequest request) {
        if (request == null || blank(request.projectId()) || blank(request.documentId())
                || blank(request.requirementVersion())) {
            throw new RequirementSemanticException("SEMANTIC_REQUEST_INVALID", "语义构建请求缺少项目、文档或版本");
        }
    }

    private String resolveProjectId(String projectId) {
        return businessProjects == null ? projectId : businessProjects.resolveProjectId(projectId);
    }

    private String resolveCollection(String projectId, String requested) {
        String configured = businessProjects != null
                ? businessProjects.requireProject(projectId).requirementCollection()
                : projectRegistry.resolveRequirementCollection(projectId);
        if (requested != null && !requested.isBlank() && !requested.trim().equals(configured)) {
            throw new RequirementSemanticException("SEMANTIC_REQUEST_INVALID",
                    "需求语义标注 collection 必须属于当前项目");
        }
        return configured;
    }

    private List<ChunkRecord> distinctParents(List<ChunkRecord> chunks) {
        Map<String, ChunkRecord> unique = new LinkedHashMap<>();
        for (ChunkRecord chunk : chunks == null ? List.<ChunkRecord>of() : chunks) {
            String key = safe(chunk.filename()) + "|" + safe(chunk.parentId()) + "|"
                    + safe(chunk.contentHash()) + "|" + chunk.parentOrder();
            unique.putIfAbsent(key, chunk);
        }
        return List.copyOf(unique.values());
    }

    /** 稳定排序：文件名 → 父块顺序 → 父块 ID → 内容哈希，保证同一输入集得到同一 sourceRevision。 */
    private List<ChunkRecord> orderedBySourceKey(List<ChunkRecord> chunks) {
        return chunks.stream()
                .sorted(java.util.Comparator
                        .comparing((ChunkRecord chunk) -> safe(chunk.filename()))
                        .thenComparingInt(ChunkRecord::parentOrder)
                        .thenComparing(chunk -> safe(chunk.parentId()))
                        .thenComparing(chunk -> safe(chunk.contentHash())))
                .toList();
    }

    /** 与标注服务同口径的输入 token 预估（中英混排约 2 字符/Token），仅用于预算预检。 */
    private int estimateInputTokens(SemanticAnnotationInput input) {
        int chars = input.rawText() == null ? 0 : input.rawText().length();
        return Math.max(1, chars / 2);
    }

    private String sourceRevision(List<ChunkRecord> chunks) {
        // 窗口策略必须纳入构建身份：仅父块不变不代表窗口不变，否则旧窗口结果会与新窗口混合。
        String strategy = String.join("|", "maxInputChars=" + properties.maxInputChars(),
                "windowOverlapChars=" + properties.windowOverlapChars(),
                "windowPlanner=" + windowPlanner.getClass().getSimpleName(),
                "structureAware=on");
        String value = strategy + "\n" + chunks.stream()
                .map(chunk -> String.join("|", safe(chunk.filename()), safe(chunk.parentId()),
                        contentHash(chunk), safe(chunk.sectionPath()),
                        Integer.toString(chunk.parentOrder())))
                .reduce("", (left, right) -> left + right + "\n");
        return sha256(value);
    }

    /** contentHash 缺失时从父块正文推导确定性哈希，避免内容变化被忽略。 */
    private String contentHash(ChunkRecord chunk) {
        String hash = chunk.contentHash();
        if (hash != null && !hash.isBlank()) return hash.trim();
        String text = chunk.parentText() == null ? "" : chunk.parentText();
        return sha256(normalizeHashText(text));
    }

    private String normalizeHashText(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    private void count(String name, String projectId, String status) {
        if (meterRegistry == null) return;
        meterRegistry.counter(name, "project", safe(projectId), "status", safe(status)).increment();
    }

    private void timer(String name, String projectId, long durationMs) {
        if (meterRegistry == null) return;
        meterRegistry.timer(name, "project", safe(projectId))
                .record(Math.max(0, durationMs), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
