package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.config.ProjectRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.project.BusinessProjectCatalogService;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.*;
import com.example.requirementrag.retrieval.EmbeddingBatcher;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.versioning.RequirementSnapshotModels.Entry;
import com.example.requirementrag.versioning.RequirementSnapshotModels.Snapshot;
import com.example.requirementrag.versioning.RequirementSnapshotRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Builds a version-scoped, reviewable graph with bounded and resumable window extraction. */
@Service
@ConditionalOnProperty(prefix = "app.rag.requirement-graph", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class RequirementGraphBuildService {
    private final SQLiteRequirementGraphStore store;
    private final RequirementGraphExtractionService extractionService;
    private final RequirementSnapshotRepository snapshots;
    private final QdrantHybridStore qdrantStore;
    private final ProjectRegistry projectRegistry;
    private final BusinessProjectCatalogService businessProjects;
    private final RequirementGraphProperties properties;
    private final RequirementGraphWindowPlanner windowPlanner;
    private final EmbeddingBatcher embeddingBatcher;
    private final RequirementGraphObservability observability;

    @Autowired
    public RequirementGraphBuildService(SQLiteRequirementGraphStore store,
                                        RequirementGraphExtractionService extractionService,
                                        RequirementSnapshotRepository snapshots,
                                        QdrantHybridStore qdrantStore,
                                        ProjectRegistry projectRegistry,
                                        ObjectProvider<BusinessProjectCatalogService> businessProjects,
                                        RequirementGraphProperties properties,
                                        ObjectProvider<RequirementGraphWindowPlanner> windowPlanner,
                                        ObjectProvider<EmbeddingBatcher> embeddingBatcher,
                                        ObjectProvider<MeterRegistry> meterRegistry) {
        this(store, extractionService, snapshots, qdrantStore, projectRegistry,
                businessProjects.getIfAvailable(), properties,
                windowPlanner.getIfAvailable(RequirementGraphWindowPlanner::new),
                embeddingBatcher.getIfAvailable(),
                new RequirementGraphObservability(meterRegistry.getIfAvailable()));
    }

    /** Compatibility constructor for focused tests and legacy callers. */
    public RequirementGraphBuildService(SQLiteRequirementGraphStore store,
                                        RequirementGraphExtractionService extractionService,
                                        RequirementSnapshotRepository snapshots,
                                        QdrantHybridStore qdrantStore,
                                        ProjectRegistry projectRegistry,
                                        RequirementGraphProperties properties) {
        this(store, extractionService, snapshots, qdrantStore, projectRegistry, null, properties,
                new RequirementGraphWindowPlanner(), null, new RequirementGraphObservability(null));
    }

    private RequirementGraphBuildService(SQLiteRequirementGraphStore store,
                                         RequirementGraphExtractionService extractionService,
                                         RequirementSnapshotRepository snapshots,
                                         QdrantHybridStore qdrantStore,
                                         ProjectRegistry projectRegistry,
                                         BusinessProjectCatalogService businessProjects,
                                         RequirementGraphProperties properties,
                                         RequirementGraphWindowPlanner windowPlanner,
                                         EmbeddingBatcher embeddingBatcher,
                                         RequirementGraphObservability observability) {
        this.store = store;
        this.extractionService = extractionService;
        this.snapshots = snapshots;
        this.qdrantStore = qdrantStore;
        this.projectRegistry = projectRegistry;
        this.businessProjects = businessProjects;
        this.properties = properties;
        this.windowPlanner = windowPlanner == null ? new RequirementGraphWindowPlanner() : windowPlanner;
        this.embeddingBatcher = embeddingBatcher;
        this.observability = observability == null ? new RequirementGraphObservability(null) : observability;
    }

    public GraphSnapshot build(BuildRequest request) {
        validate(request);
        long startedAt = System.nanoTime();
        String projectId = resolveProjectId(request.projectId());
        observability.count("nexus.requirement_graph.build.started", projectId, "started");
        validatePrivacyPolicy(projectId);
        String collection = resolveCollection(projectId, request.collection());
        List<ChunkRecord> chunks = loadChunks(projectId, collection, request.documentId(), request.requirementVersion());
        if (chunks.isEmpty()) {
            throw new RequirementGraphException("GRAPH_INPUT_EMPTY", "需求版本没有可构建语义图的父块");
        }

        String sourceRevision = sourceRevision(chunks);
        GraphSnapshot previous = request.resumeSnapshotId() == null || request.resumeSnapshotId().isBlank()
                ? null : store.requireSnapshot(request.resumeSnapshotId().trim());
        if (previous != null && (!projectId.equals(previous.businessProjectId())
                || !request.documentId().equals(previous.documentId())
                || !request.requirementVersion().equals(previous.requirementVersion())
                || !sourceRevision.equals(previous.sourceRevision()))) {
            throw new RequirementGraphException("GRAPH_SNAPSHOT_STALE", "恢复快照与当前需求版本不匹配");
        }
        if (previous != null && (previous.status() == SnapshotStatus.PUBLISHED
                || previous.status() == SnapshotStatus.VERIFIED
                || previous.status() == SnapshotStatus.REVIEW_REQUIRED)) {
            throw new RequirementGraphException("GRAPH_SNAPSHOT_IMMUTABLE",
                    "已发布/已审核快照不可作为恢复目标，请直接发起新构建");
        }
        String buildId = request.buildId() != null && !request.buildId().isBlank()
                ? request.buildId()
                : previous != null && previous.buildId() != null && !previous.buildId().isBlank()
                ? previous.buildId() : "graph-build:" + UUID.randomUUID();
        // 快照身份采用“内容/配置身份”，buildId 只属于一次具体构建任务：
        // 相同输入重复构建复用同一快照（幂等），不同输入/配置生成不同快照。
        // 老库中 v2 快照 ID 曾包含 buildId，这里优先按业务唯一域复用旧 ID，避免唯一约束冲突。
        String snapshotId;
        if (previous != null) {
            snapshotId = previous.id();
        } else {
            String extractionPrompt = properties.extractionPromptVersion();
            snapshotId = store.findSnapshotByScope(projectId, request.documentId(), request.requirementVersion(),
                            sourceRevision, extractionPrompt)
                    .map(GraphSnapshot::id)
                    .orElseGet(() -> "reqgraph:" + sha256(projectId + "|" + request.documentId() + "|"
                            + request.requirementVersion() + "|" + sourceRevision + "|" + properties.ontologyVersion()
                            + "|" + extractionPrompt).substring(0, 40));
        }

        List<PlannedChunk> planned = planChunks(chunks);
        int windowCount = planned.stream().mapToInt(item -> item.plan().windowCount()).sum();
        if (windowCount > properties.maxWindows()) {
            throw new RequirementGraphException("GRAPH_WINDOW_TOO_LARGE", "需求语义图窗口数量超过构建上限");
        }
        double coverage = coverage(planned);
        Instant now = Instant.now();
        GraphSnapshot building = new GraphSnapshot(snapshotId, projectId, request.documentId(),
                request.requirementVersion(), sourceRevision, extractionModel(), properties.extractionPromptVersion(),
                SnapshotStatus.BUILDING, 0, 0, now, now, null, properties.schemaVersion(),
                properties.ontologyVersion(), coverage, windowCount, 0, 0, 0, buildId, null, null, null);
        Optional<GraphSnapshot> existing = store.findSnapshotById(snapshotId);
        if (existing.isPresent()) {
            GraphSnapshot current = existing.get();
            if (current.status() == SnapshotStatus.PUBLISHED
                    || current.status() == SnapshotStatus.VERIFIED
                    || current.status() == SnapshotStatus.REVIEW_REQUIRED) {
                // 幂等复用：同一内容/配置已完成构建，直接返回已有快照，不修改审核/发布结果。
                observability.count("nexus.requirement_graph.build.reused", projectId, current.status().name());
                return current;
            }
        }
        store.saveSnapshot(building);
        store.saveWindows(snapshotId, planned.stream().flatMap(item -> item.plan().windows().stream())
                .map(window -> newWindowView(snapshotId, window, WindowStatus.PENDING, 0, null, null, null)).toList());

        Map<String, ExtractionResult> completed = store.windowResults(snapshotId);
        BuildAccumulator accumulator = new BuildAccumulator(snapshotId, projectId, request.requirementVersion());
        int succeeded = 0;
        int failed = 0;
        int warnings = 0;
        long startedNanos = System.nanoTime();
        int modelCalls = completed.size();
        long usedTokens = 0;
        List<RequirementGraphWindow> allWindows = planned.stream().flatMap(item -> item.plan().windows().stream()).toList();
        for (RequirementGraphWindow window : allWindows) {
            if (Thread.currentThread().isInterrupted()) {
                throw new RequirementGraphException("GRAPH_BUILD_CANCELLED", "需求图构建已取消");
            }
            ExtractionResult prior = completed.get(window.id());
            if (prior != null) {
                accumulator.add(window, prior);
                succeeded++;
                reportProgress(request, succeeded, failed, allWindows.size());
                continue;
            }
            long estimatedTokens = estimateTokens(window.text());
            if (modelCalls >= properties.maxModelCalls()
                    || elapsedSeconds(startedNanos) >= properties.maxWallClockSeconds()
                    || (properties.maxEstimatedTokens() > 0 && usedTokens + estimatedTokens > properties.maxEstimatedTokens())) {
                failed++;
                warnings++;
                markWindow(snapshotId, window, WindowStatus.FAILED, properties.maxRetries() + 1,
                        "GRAPH_BUDGET_EXCEEDED", null);
                reportProgress(request, succeeded, failed, allWindows.size());
                continue;
            }
            usedTokens += estimatedTokens;
            ExtractionAttempt attempt = extractWithRetry(snapshotId, window, modelCalls);
            modelCalls += attempt.calls();
            if (attempt.result() == null) {
                failed++;
                warnings++;
                markWindow(snapshotId, window, WindowStatus.FAILED, attempt.calls(), attempt.errorCode(), null);
            } else {
                succeeded++;
                accumulator.add(window, attempt.result());
                store.saveWindowResult(snapshotId, window.id(), attempt.result());
                markWindow(snapshotId, window, WindowStatus.SUCCEEDED, attempt.calls(), null, Instant.now());
            }
            reportProgress(request, succeeded, failed, allWindows.size());
        }

        List<Entity> entities = accumulator.entities();
        List<Relation> relations = accumulator.relations();
        List<Evidence> evidence = accumulator.evidence();
        List<Uncertainty> uncertainties = accumulator.uncertainties();
        List<Conflict> conflicts = accumulator.conflicts();
        if (entities.isEmpty() && failed == 0) {
            store.updateStatus(snapshotId, SnapshotStatus.FAILED, null);
            throw new RequirementGraphBuildFailureException("GRAPH_SCHEMA_INVALID", "需求语义图抽取未产生实体", snapshotId);
        }
        boolean allowPartial = Boolean.TRUE.equals(request.allowPartial()) || properties.allowPartialBuild();
        SnapshotStatus finalStatus = failed == 0 ? SnapshotStatus.REVIEW_REQUIRED
                : allowPartial && !entities.isEmpty() ? SnapshotStatus.PARTIAL_FAILED : SnapshotStatus.FAILED;
        GraphSnapshot result = new GraphSnapshot(snapshotId, projectId, request.documentId(), request.requirementVersion(),
                sourceRevision, extractionModel(), properties.extractionPromptVersion(), finalStatus,
                entities.size(), relations.size(), now, Instant.now(), null, properties.schemaVersion(),
                properties.ontologyVersion(), coverage, windowCount, succeeded, failed,
                warnings + uncertainties.size() + conflicts.size(), buildId, null, null, null);
        store.saveDraftSnapshot(result, entities, relations, evidence, uncertainties, conflicts);
        if (properties.hybridRetrievalEnabled() && embeddingBatcher != null) {
            try {
                embedClaims(snapshotId, entities, relations);
            } catch (RuntimeException exception) {
                warnings++;
                // Embedding 失败必须回写快照 warning 数，避免返回的快照元数据与真实可用性不一致。
                store.updateSnapshotWarningCount(snapshotId, warnings + uncertainties.size() + conflicts.size());
            }
        }
        if (failed > 0 && !allowPartial) {
            store.updateStatus(snapshotId, SnapshotStatus.FAILED, null, "存在失败窗口");
            observability.count("nexus.requirement_graph.build.completed", projectId, "failed");
            observability.timer("nexus.requirement_graph.build.duration", projectId, "failed", elapsedMillis(startedAt));
            throw new RequirementGraphBuildFailureException("GRAPH_PARTIAL_FAILURE", "需求语义图存在失败窗口，请修复后恢复构建", snapshotId);
        }
        String resultStatus = finalStatus.name();
        observability.count("nexus.requirement_graph.build.completed", projectId, resultStatus);
        observability.value("nexus.requirement_graph.windows", projectId, resultStatus, windowCount);
        observability.value("nexus.requirement_graph.entities", projectId, resultStatus, entities.size());
        observability.value("nexus.requirement_graph.relations", projectId, resultStatus, relations.size());
        observability.value("nexus.requirement_graph.evidence_resolution", projectId, resultStatus,
                evidence.stream().filter(item -> item.resolutionStatus() == EvidenceResolutionStatus.RESOLVED).count());
        observability.timer("nexus.requirement_graph.build.duration", projectId, resultStatus, elapsedMillis(startedAt));
        return store.requireSnapshot(snapshotId);
    }

    private List<PlannedChunk> planChunks(List<ChunkRecord> chunks) {
        return chunks.stream().map(chunk -> new PlannedChunk(chunk,
                windowPlanner.plan(chunk, properties.maxInputChars(), properties.windowOverlapChars()))).toList();
    }

    private double coverage(List<PlannedChunk> planned) {
        int chars = 0;
        int covered = 0;
        for (PlannedChunk item : planned) { chars += item.plan().sourceChars(); covered += item.plan().coveredChars(); }
        return chars == 0 ? 1.0 : Math.min(1.0, (double) covered / chars);
    }

    private ExtractionAttempt extractWithRetry(String snapshotId, RequirementGraphWindow window, int modelCalls) {
        markWindow(snapshotId, window, WindowStatus.RUNNING, 1, null, Instant.now());
        int calls = 0;
        String lastCode = "GRAPH_WINDOW_FAILED";
        while (calls <= properties.maxRetries() && modelCalls + calls < properties.maxModelCalls()) {
            calls++;
            try {
                ExtractionResult result = extractionService.extract(new ExtractionInput(window.filename(), window.parentId(),
                        window.parentOrder(), window.sectionPath(), window.heading(), window.contentHash(), window.text(),
                        window.id(), window.startOffset(), window.endOffset()));
                return new ExtractionAttempt(result, calls, null);
            } catch (RuntimeException exception) {
                lastCode = classify(exception);
                if (!retryable(lastCode) || calls > properties.maxRetries()) break;
                try { Thread.sleep(Math.min(1_000L, 100L * (1L << Math.min(calls - 1, 3)))); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
        }
        return new ExtractionAttempt(null, calls, lastCode);
    }

    /** 异步任务进度回写；同步构建（buildId 为空）时跳过。 */
    private void reportProgress(BuildRequest request, int succeeded, int failed, int total) {
        if (request == null || request.buildId() == null || request.buildId().isBlank()) return;
        store.updateBuildJobProgress(request.buildId(), succeeded + failed, total);
    }

    private void markWindow(String snapshotId, RequirementGraphWindow window, WindowStatus status,
                            int attempts, String errorCode, Instant completedAt) {
        store.updateWindow(snapshotId, new RequirementGraphWindowView(window.id(), snapshotId, window.filename(),
                window.parentId(), window.sectionPath(), window.heading(), window.windowIndex(), window.startOffset(),
                window.endOffset(), window.contentHash(), status, attempts, errorCode,
                status == WindowStatus.RUNNING ? Instant.now() : null, completedAt, window.continuationOf()));
    }

    private void embedClaims(String snapshotId, List<Entity> entities, List<Relation> relations) {
        List<String> entityTexts = entities.stream().map(item -> item.displayName() + " " + item.description()).toList();
        List<float[]> entityVectors = embeddingBatcher.embedAll(entityTexts);
        Map<String, float[]> entityMap = new LinkedHashMap<>();
        for (int index = 0; index < Math.min(entities.size(), entityVectors.size()); index++) entityMap.put(entities.get(index).id(), entityVectors.get(index));
        store.saveEntityEmbeddings(snapshotId, entityMap);
        List<String> relationTexts = relations.stream().map(Relation::statement).toList();
        List<float[]> relationVectors = embeddingBatcher.embedAll(relationTexts);
        Map<String, float[]> relationMap = new LinkedHashMap<>();
        for (int index = 0; index < Math.min(relations.size(), relationVectors.size()); index++) relationMap.put(relations.get(index).id(), relationVectors.get(index));
        store.saveRelationEmbeddings(snapshotId, relationMap);
    }

    private void validatePrivacyPolicy(String projectId) {
        RequirementGraphProperties.ProjectPolicy policy = properties.projectPolicies().get(projectId);
        if (properties.privacyPolicyRequired() && policy == null) {
            throw new RequirementGraphException("GRAPH_PRIVACY_POLICY_BLOCKED", "业务项目未配置需求图隐私策略");
        }
        if (policy != null && !policy.enabled()) {
            throw new RequirementGraphException("GRAPH_PRIVACY_POLICY_BLOCKED", "业务项目需求图构建未获准");
        }
        if (policy != null && policy.externalTransmissionAllowed() && !properties.externalTransmissionAllowed()) {
            throw new RequirementGraphException("GRAPH_PRIVACY_POLICY_BLOCKED", "全局策略禁止向外部模型传输需求内容");
        }
    }

    private List<ChunkRecord> loadChunks(String projectId, String collection, String documentId, String version) {
        try {
            java.util.Optional<Snapshot> snapshot = snapshots.materialize(snapshotNamespace(projectId), documentId, version);
            if (snapshot.isPresent() && !snapshot.get().entries().isEmpty()) {
                return distinctParents(snapshot.get().entries().stream().map(this::chunk).toList());
            }
        } catch (RuntimeException ignored) {
            // Compatibility fallback: current published Qdrant payload remains readable.
        }
        return distinctParents(qdrantStore.scrollVersion(collection, documentId, version));
    }

    private ChunkRecord chunk(Entry entry) {
        return new ChunkRecord(entry.entryId() + "-child", "", "", entry.filename(), entry.entryId(), entry.text(), entry.text(),
                entry.contentHash(), entry.parentOrder(), 0);
    }

    private List<ChunkRecord> distinctParents(List<ChunkRecord> chunks) {
        Map<String, ChunkRecord> unique = new LinkedHashMap<>();
        for (ChunkRecord chunk : chunks == null ? List.<ChunkRecord>of() : chunks) {
            String key = safe(chunk.filename()) + "|" + safe(chunk.parentId()) + "|" + safe(chunk.contentHash())
                    + "|" + chunk.parentOrder();
            unique.putIfAbsent(key, chunk);
        }
        return List.copyOf(unique.values());
    }

    private RequirementGraphWindowView newWindowView(String snapshotId, RequirementGraphWindow window,
                                                      WindowStatus status, int attempts, String code,
                                                      Instant started, Instant completed) {
        return new RequirementGraphWindowView(window.id(), snapshotId, window.filename(), window.parentId(), window.sectionPath(),
                window.heading(), window.windowIndex(), window.startOffset(), window.endOffset(), window.contentHash(),
                status, attempts, code, started, completed, window.continuationOf());
    }

    private String resolveProjectId(String projectId) { return businessProjects == null ? projectId : businessProjects.resolveProjectId(projectId); }
    private String snapshotNamespace(String projectId) { return businessProjects == null ? projectId : businessProjects.requireProject(projectId).requirementSnapshotNamespace(); }
    private String resolveCollection(String projectId, String requested) {
        String configured = businessProjects != null ? businessProjects.requireProject(projectId).requirementCollection() : projectRegistry.resolveRequirementCollection(projectId);
        if (requested != null && !requested.isBlank() && !requested.trim().equals(configured)) throw new RequirementGraphException("GRAPH_INPUT_EMPTY", "需求语义图 collection 必须属于当前项目");
        return configured;
    }
    private String extractionModel() { return extractionService.resolvedModel(); }

    /** 粗略估算单窗口抽取的 Token 消耗（正文 + 系统提示/JSON 输出开销），用于 maxEstimatedTokens 预算。 */
    private long estimateTokens(String text) {
        long chars = text == null ? 0 : text.length();
        return Math.max(1, chars / 4L + 200L);
    }

    private String sourceRevision(List<ChunkRecord> chunks) {
        String value = chunks.stream().sorted(Comparator.comparing(ChunkRecord::filename, Comparator.nullsFirst(String::compareTo)).thenComparingInt(ChunkRecord::parentOrder).thenComparing(ChunkRecord::parentId, Comparator.nullsFirst(String::compareTo))).map(chunk -> String.join("|", safe(chunk.filename()), safe(chunk.parentId()), safe(chunk.contentHash()), safe(chunk.sectionPath()), Integer.toString(chunk.parentOrder()))).reduce("", (left, right) -> left + right + "\n");
        return sha256(value);
    }
    private void validate(BuildRequest request) { if (request == null || blank(request.projectId()) || blank(request.documentId()) || blank(request.requirementVersion())) throw new RequirementGraphException("GRAPH_INPUT_EMPTY", "需求语义图构建请求不完整"); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String safe(String value) { return value == null ? "" : value.trim(); }
    private long elapsedSeconds(long started) { return Duration.ofNanos(System.nanoTime() - started).toSeconds(); }
    private long elapsedMillis(long started) { return Duration.ofNanos(System.nanoTime() - started).toMillis(); }
    private boolean retryable(String code) { return code.equals("GRAPH_MODEL_TIMEOUT") || code.equals("GRAPH_MODEL_RATE_LIMITED") || code.equals("GRAPH_MODEL_UNAVAILABLE"); }
    private String classify(RuntimeException exception) {
        if (exception instanceof RequirementGraphException graphException) return graphException.code();
        String text = (exception.getClass().getSimpleName() + " " + exception.getMessage()).toLowerCase(Locale.ROOT);
        if (text.contains("timeout") || text.contains("timed out")) return "GRAPH_MODEL_TIMEOUT";
        if (text.contains("429") || text.contains("rate")) return "GRAPH_MODEL_RATE_LIMITED";
        if (text.contains("503") || text.contains("502") || text.contains("unavailable") || text.contains("connection")) return "GRAPH_MODEL_UNAVAILABLE";
        if (text.contains("schema") || text.contains("evidence") || text.contains("quote")) return "GRAPH_EVIDENCE_INVALID";
        return "GRAPH_WINDOW_FAILED";
    }
    private static String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); } }

    private record PlannedChunk(ChunkRecord chunk, RequirementGraphWindowPlanner.Plan plan) {}
    private record ExtractionAttempt(ExtractionResult result, int calls, String errorCode) {}

    private final class BuildAccumulator {
        private final String snapshotId;
        private final String projectId;
        private final String version;
        private final Map<String, EntityAccumulator> entities = new LinkedHashMap<>();
        private final Map<String, RelationAccumulator> relations = new LinkedHashMap<>();
        private final Map<String, Evidence> evidence = new LinkedHashMap<>();
        private final List<Uncertainty> uncertainties = new ArrayList<>();
        private final Map<String, Conflict> conflicts = new LinkedHashMap<>();
        private final Map<String, String> entityKeyToId = new HashMap<>();

        private BuildAccumulator(String snapshotId, String projectId, String version) { this.snapshotId = snapshotId; this.projectId = projectId; this.version = version; }

        private void add(RequirementGraphWindow window, ExtractionResult extracted) {
            String windowPrefix = projectId + "|" + version + "|" + window.filename() + "|" + window.parentId() + "|" + window.contentHash() + "|" + window.startOffset() + "|" + window.endOffset();
            List<String> uncertaintyIds = new ArrayList<>();
            for (String message : extracted.uncertainties()) {
                String id = "uncertainty:" + sha256(snapshotId + "|" + window.id() + "|" + message).substring(0, 32);
                uncertaintyIds.add(id);
                uncertainties.add(new Uncertainty(id, snapshotId, window.id(), "MODEL_UNCERTAINTY", message,
                        List.of(), ClaimStatus.INFERRED, Instant.now()));
            }
            Map<String, String> localToGlobal = new LinkedHashMap<>();
            for (ExtractedEntity item : extracted.entities()) {
                String canonical = canonical(item.name());
                String context = context(window.sectionPath(), window.heading());
                String key = item.type() + "|" + canonical + "|" + context;
                String resolvedKey = resolveEntityKey(item.type(), canonical, context, key);
                String globalId = entityKeyToId.computeIfAbsent(resolvedKey,
                        ignored -> "entity:" + sha256(snapshotId + "|" + resolvedKey).substring(0, 40));
                localToGlobal.put(item.localId(), globalId);
                EntityAccumulator entity = entities.computeIfAbsent(globalId,
                        ignored -> new EntityAccumulator(globalId, snapshotId, item.type(), canonical, item.name().trim(), context, window.id()));
                List<String> evidenceIds = evidenceFor(window, item.evidenceQuotes(), windowPrefix);
                entity.add(item, evidenceIds, uncertaintyIds, window.id(), window.parentId(), window.contentHash());
            }
            for (ExtractedRelation item : extracted.relations()) {
                String source = localToGlobal.get(item.sourceLocalId());
                String target = localToGlobal.get(item.targetLocalId());
                if (source == null || target == null) throw new RequirementGraphException("GRAPH_SCHEMA_INVALID", "需求语义图关系端点未解析");
                String relationId = "relation:" + sha256(snapshotId + "|" + source + "|" + item.type() + "|" + target).substring(0, 40);
                RelationType relationType = RelationType.valueOf(item.type());
                EntityType sourceType = entities.values().stream().filter(value -> value.id.equals(source)).findFirst().map(value -> EntityType.valueOf(value.type)).orElse(null);
                EntityType targetType = entities.values().stream().filter(value -> value.id.equals(target)).findFirst().map(value -> EntityType.valueOf(value.type)).orElse(null);
                RequirementGraphOntology.validate(relationType, sourceType, targetType);
                RelationAccumulator relation = relations.computeIfAbsent(relationId,
                        ignored -> new RelationAccumulator(relationId, snapshotId, source, relationType, target, item.statement(), item.condition(), item.scenario()));
                List<String> evidenceIds = evidenceFor(window, item.evidenceQuotes(), windowPrefix);
                relation.add(item, evidenceIds, uncertaintyIds);
            }
        }

        private List<String> evidenceFor(RequirementGraphWindow window, List<String> quotes, String prefix) {
            List<String> ids = new ArrayList<>();
            for (String quote : quotes == null ? List.<String>of() : quotes) {
                RequirementGraphEvidence.Span span = RequirementGraphEvidence.resolve(window.text(), quote, window.startOffset());
                String id = RequirementGraphEvidence.spanId(projectId, version, window.filename(), window.parentId(),
                        window.parentOrder(), window.contentHash(), span.startOffset(), span.endOffset(), quote);
                evidence.putIfAbsent(id, new Evidence(id, window.filename(), window.parentId(), window.parentOrder(), version,
                        RequirementGraphEvidence.excerpt(window.text(), 600), window.contentHash(), window.sectionPath(),
                        span.quote(), span.startOffset(), span.endOffset(), span.status()));
                ids.add(id);
            }
            return List.copyOf(ids);
        }

        private String resolveEntityKey(String type, String canonical, String context, String exactKey) {
            if (entityKeyToId.containsKey(exactKey)) return exactKey;
            for (Map.Entry<String, String> candidate : entityKeyToId.entrySet()) {
                String[] parts = candidate.getKey().split("\\|", -1);
                if (parts.length != 3 || !parts[0].equals(type) || !parts[2].equals(context)) continue;
                EntityAccumulator existing = entities.get(candidate.getValue());
                if (existing == null) continue;
                if (existing.matchesAlias(canonical)) return candidate.getKey();
            }
            return exactKey;
        }

        private List<Entity> entities() { return entities.values().stream().map(EntityAccumulator::value).toList(); }
        private List<Relation> relations() { return relations.values().stream().map(RelationAccumulator::value).toList(); }
        private List<Evidence> evidence() { return List.copyOf(evidence.values()); }
        private List<Uncertainty> uncertainties() { return List.copyOf(uncertainties); }
        private List<Conflict> conflicts() { return List.copyOf(conflicts.values()); }

        private String context(String sectionPath, String heading) { String value = safe(sectionPath); if (!value.isBlank()) { int index = value.indexOf(" / "); return (index > 0 ? value.substring(0, index) : value).toLowerCase(Locale.ROOT); } return safe(heading).toLowerCase(Locale.ROOT); }
        private String canonical(String value) { return value == null ? "" : value.trim().replaceAll("\s+", "").toLowerCase(Locale.ROOT); }

        private final class EntityAccumulator {
            private final String id; private final String snapshotId; private final String type; private final String canonicalName; private final String displayName; private final String context; private final String firstWindow;
            private final Set<String> aliases = new LinkedHashSet<>(); private final Set<String> evidenceIds = new LinkedHashSet<>(); private final Set<String> parentIds = new LinkedHashSet<>(); private final Set<String> contentHashes = new LinkedHashSet<>(); private final Set<String> uncertaintyIds = new LinkedHashSet<>(); private String description = ""; private double confidence; private int observations; private String lastWindow;
            private EntityAccumulator(String id, String snapshotId, String type, String canonicalName, String displayName, String context, String firstWindow) { this.id=id; this.snapshotId=snapshotId; this.type=type; this.canonicalName=canonicalName; this.displayName=displayName; this.context=context; this.firstWindow=firstWindow; }
            private boolean matchesAlias(String canonical) {
                if (canonicalName.equals(canonical)) return true;
                return aliases.stream().map(value -> value == null ? "" : value.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT))
                        .anyMatch(canonical::equals);
            }

            private void add(ExtractedEntity item, List<String> evidence, List<String> uncertainties,
                             String windowId, String parentId, String contentHash) {
                aliases.addAll(item.aliases()); evidenceIds.addAll(evidence); uncertaintyIds.addAll(uncertainties);
                observations++; lastWindow=windowId; confidence=Math.max(confidence,item.confidence());
                if (description.isBlank()) description=item.description();
                if (parentId != null && !parentId.isBlank()) parentIds.add(parentId);
                if (contentHash != null && !contentHash.isBlank()) contentHashes.add(contentHash);
            }
            private Entity value() { ClaimStatus claim = uncertaintyIds.isEmpty() ? ClaimStatus.EXTRACTED : ClaimStatus.INFERRED; return new Entity(id,snapshotId,EntityType.valueOf(type),canonicalName,displayName,List.copyOf(aliases),description,List.copyOf(evidenceIds),List.copyOf(parentIds),List.copyOf(contentHashes),confidence,observations>1?EntityStatus.NORMALIZED:EntityStatus.EXTRACTED,claim,null,context,firstWindow,lastWindow,List.copyOf(uncertaintyIds),List.of(),null,null,null); }
        }

        private final class RelationAccumulator {
            private final String id; private final String snapshotId; private final String source; private final RelationType type; private final String target; private final String statement; private final String condition; private final String scenario; private final Set<String> evidenceIds = new LinkedHashSet<>(); private final Set<String> variants = new LinkedHashSet<>(); private final Set<String> uncertaintyIds = new LinkedHashSet<>(); private final Set<String> conflicts = new LinkedHashSet<>(); private double confidence;
            private RelationAccumulator(String id,String snapshotId,String source,RelationType type,String target,String statement,String condition,String scenario) { this.id=id;this.snapshotId=snapshotId;this.source=source;this.type=type;this.target=target;this.statement=statement;this.condition=condition;this.scenario=scenario;variants.add(statement); }
            private void add(ExtractedRelation item,List<String> evidence,List<String> uncertainties) { evidenceIds.addAll(evidence); uncertaintyIds.addAll(uncertainties); confidence=Math.max(confidence,item.confidence()); if (!variants.contains(item.statement())) { variants.add(item.statement()); String conflictId="conflict:"+sha256(id+"|"+item.statement()).substring(0,32); conflicts.add(conflictId); BuildAccumulator.this.conflicts.putIfAbsent(conflictId,new Conflict(conflictId,snapshotId,"RELATION_STATEMENT_VARIANT",List.of(id),"关系存在不同声明版本",ClaimStatus.CONFLICTED,Instant.now())); } }
            private Relation value() { ClaimStatus claim=!conflicts.isEmpty()?ClaimStatus.CONFLICTED:uncertaintyIds.isEmpty()?ClaimStatus.EXTRACTED:ClaimStatus.INFERRED; RelationStatus legacy=claim==ClaimStatus.CONFLICTED?RelationStatus.AMBIGUOUS:RelationStatus.EXTRACTED; return new Relation(id,snapshotId,source,type,target,statement,List.copyOf(evidenceIds),confidence,legacy,null,null,claim,condition,scenario,List.copyOf(variants),List.copyOf(uncertaintyIds),List.copyOf(conflicts),null); }
        }
    }
}
