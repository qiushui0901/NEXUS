package com.example.requirementrag.evaluation;

import com.example.requirementrag.config.VersioningProperties;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.DriftDecision;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldCase;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.GoldWindow;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictedRelation;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.Prediction;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PredictionStatus;
import com.example.requirementrag.evaluation.RequirementGraphGoldModels.PublicationDecision;
import com.example.requirementrag.requirement.graph.RequirementGraphBuildService;
import com.example.requirementrag.requirement.graph.RequirementGraphException;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.BuildRequest;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Entity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractionResult;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Relation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphProperties;
import com.example.requirementrag.requirement.graph.SQLiteRequirementGraphStore;
import com.example.requirementrag.versioning.RequirementSnapshotModels.Entry;
import com.example.requirementrag.versioning.RequirementSnapshotModels.Snapshot;
import com.example.requirementrag.versioning.RequirementSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 生产构建链路预测器：把每个金标用例通过<b>真实 {@link RequirementGraphBuildService#build}</b> 跑一遍。
 *
 * <p>与 {@link ProductionGraphPredictor}（只走逐窗口抽取+合并）相比，本预测器覆盖完整生产链路：
 * <ul>
 *   <li>{@code RequirementGraphWindowPlanner} 窗口规划；</li>
 *   <li>真实 {@code RequirementGraphExtractionService}（LLM + Schema/证据/本体校验）；</li>
 *   <li>{@code BuildAccumulator} 跨窗口实体/关系合并与别名融合；</li>
 *   <li>{@code RequirementGraphEvidence} 证据解析 + {@code SQLiteRequirementGraphStore} 持久化；</li>
 *   <li>快照状态/审核流转（BUILDING → REVIEW_REQUIRED / PARTIAL_FAILED）。</li>
 * </ul>
 *
 * <p>金标窗口/文本会被写入 {@link SnapshotSource} 后触发真实构建，因此不需要预先存在的需求版本数据。
 * 构造方需要自行提供已组装好的 {@code RequirementGraphBuildService}（测试/评测入口可用 Mockito 桩
 * qdrant/projectRegistry，主代码不依赖 Mockito）。
 */
public class ProductionBuildGraphPredictor implements RequirementGraphGoldPredictor {

    private static final String PROJECT_ID = "gold-eval";
    private static final String COLLECTION = "requirements_gold";
    private static final String VERSION = "v1";

    private final RequirementGraphBuildService buildService;
    private final SQLiteRequirementGraphStore store;
    private final SnapshotSource snapshots;

    public ProductionBuildGraphPredictor(RequirementGraphBuildService buildService,
                                         SQLiteRequirementGraphStore store,
                                         SnapshotSource snapshots) {
        this.buildService = buildService;
        this.store = store;
        this.snapshots = snapshots;
    }

    /** 需求快照来源：允许评测入口注入每一条金标用例对应的合成快照。 */
    public interface SnapshotSource {
        void putSnapshot(String projectId, String documentId, String requirementVersion, Snapshot snapshot);
    }

    /** 进程内 Map 版快照仓库：不依赖 Spring 文件布局，供评测/测试注入合成快照。 */
    public static class MapRequirementSnapshotRepository extends RequirementSnapshotRepository
            implements SnapshotSource {
        private final Map<String, Snapshot> snapshots = new java.util.concurrent.ConcurrentHashMap<>();

        public MapRequirementSnapshotRepository(Path tempDirectory) {
            super(new ObjectMapper(), new VersioningProperties(tempDirectory.toString()));
        }

        @Override
        public Optional<Snapshot> materialize(String projectId, String documentId, String requirementVersion) {
            return Optional.ofNullable(snapshots.get(key(projectId, documentId, requirementVersion)));
        }

        @Override
        public void putSnapshot(String projectId, String documentId, String requirementVersion, Snapshot snapshot) {
            snapshots.put(key(projectId, documentId, requirementVersion), snapshot);
        }

        private static String key(String projectId, String documentId, String requirementVersion) {
            return projectId + "|" + documentId + "|" + requirementVersion;
        }
    }

    @Override
    public Prediction predict(GoldCase goldCase) {
        long startNanos = System.nanoTime();
        String projectId = blank(goldCase.projectId()) ? PROJECT_ID : goldCase.projectId();
        String documentId = "gold-" + goldCase.caseId();
        String version = blank(goldCase.requirementVersion()) ? VERSION : goldCase.requirementVersion();
        List<Entry> entries = toEntries(goldCase);
        if (entries.isEmpty()) {
            return Prediction.empty();
        }
        Snapshot snapshot = new Snapshot(1, projectId, documentId, version, null, List.of(),
                Instant.now().toString(), List.of(), entries);
        snapshots.putSnapshot(projectId, documentId, version, snapshot);
        try {
            GraphSnapshot built = buildService.build(new BuildRequest(projectId, documentId, version, COLLECTION));
            String snapshotId = built.id();
            List<Entity> entities = store.allEntities(snapshotId, 10_000);
            List<Relation> relations = store.allRelations(snapshotId, 10_000);
            List<String> uncertainties = new ArrayList<>();
            for (ExtractionResult result : store.windowResults(snapshotId).values()) {
                uncertainties.addAll(result.uncertainties());
            }
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            Map<String, String> idToName = new LinkedHashMap<>();
            Set<RequirementGraphGoldModels.PredictedEntity> entitySet = new LinkedHashSet<>();
            for (Entity entity : entities) {
                String name = entity.displayName() == null || entity.displayName().isBlank()
                        ? entity.canonicalName() : entity.displayName();
                idToName.putIfAbsent(entity.id(), name);
                entitySet.add(new RequirementGraphGoldModels.PredictedEntity(
                        entity.type() == null ? "" : entity.type().name(), name, entity.aliases()));
            }
            List<PredictedRelation> predictedRelations = new ArrayList<>();
            for (Relation relation : relations) {
                String source = idToName.getOrDefault(relation.sourceEntityId(), relation.sourceEntityId());
                String target = idToName.getOrDefault(relation.targetEntityId(), relation.targetEntityId());
                predictedRelations.add(new PredictedRelation(source, target, relation.type().name()));
            }
            boolean empty = entitySet.isEmpty() && predictedRelations.isEmpty();
            PredictionStatus status = built.status() == SnapshotStatus.PARTIAL_FAILED
                    ? PredictionStatus.FAILURE : empty ? PredictionStatus.EMPTY_RESULT : PredictionStatus.SUCCESS;
            return new Prediction(entitySet, predictedRelations, List.of(), uncertainties, List.of(),
                    new DriftDecision("", "", "", List.of()), PublicationDecision.NOT_PUBLISHED,
                    status, "", latencyMs, 0);
        } catch (RequirementGraphException exception) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            return failure(exception.code(), latencyMs);
        } catch (RuntimeException exception) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            return failure(classify(exception), latencyMs);
        }
    }

    private List<Entry> toEntries(GoldCase goldCase) {
        List<Entry> entries = new ArrayList<>();
        if (goldCase.windows() != null && !goldCase.windows().isEmpty()) {
            int order = 1;
            for (GoldWindow window : goldCase.windows()) {
                // 用真实父块 id 作为 entryId，BuildService 转 ChunkRecord 后 parentId 得以保留；
                // parentOrder / contentHash / filename 也来自真实窗口。
                String parentId = blank(window.parentId()) ? "window:" + window.windowId() : window.parentId();
                entries.add(new Entry(parentId, window.filename(),
                        window.parentOrder() == 0 ? order : window.parentOrder(), window.text(), window.contentHash()));
                order++;
            }
        } else if (goldCase.inputText() != null && !goldCase.inputText().isBlank()) {
            entries.add(new Entry("gold-case", "gold-case", 1, goldCase.inputText(), "gold-content"));
        }
        return entries;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private Prediction failure(String errorCode, long latencyMs) {
        return new Prediction(Set.of(), List.of(), List.of(), List.of(), List.of(),
                new DriftDecision("", "", "", List.of()), PublicationDecision.NOT_PUBLISHED,
                PredictionStatus.FAILURE, errorCode, latencyMs, 0);
    }

    private String classify(RuntimeException exception) {
        String text = (exception.getClass().getSimpleName() + " "
                + (exception.getMessage() == null ? "" : exception.getMessage())).toLowerCase(Locale.ROOT);
        if (text.contains("timeout") || text.contains("timed out")) return "GRAPH_MODEL_TIMEOUT";
        if (text.contains("429") || text.contains("rate limit") || text.contains("rate_limit")) return "GRAPH_MODEL_RATE_LIMITED";
        if (text.contains("unavailable") || text.contains("connection")) return "GRAPH_MODEL_UNAVAILABLE";
        return "GRAPH_BUILD_FAILED";
    }

    /** 复制配置并把数据库路径指向独立临时文件，避免评测污染真实 requirement-graph.db。 */
    public static RequirementGraphProperties withDatabasePath(RequirementGraphProperties properties, String path) {
        return new RequirementGraphProperties(
                properties.enabled(), properties.extractionEnabled(), properties.retrievalEnabled(), path,
                properties.maxEntitiesPerChunk(), properties.maxRelationsPerChunk(), properties.maxInputChars(),
                properties.maxHops(), properties.candidateLimit(), properties.extractionModel(),
                properties.extractionPromptVersion(), properties.ontologyVersion(), properties.schemaVersion(),
                properties.windowOverlapChars(), properties.maxWindows(), properties.maxModelCalls(),
                properties.maxRetries(), properties.maxWallClockSeconds(), properties.maxEstimatedTokens(),
                properties.maxConcurrentWorkers(), properties.maxGraphRows(), properties.allowPartialBuild(),
                properties.shadowBuild(), properties.shadowQuery(), properties.hybridRetrievalEnabled(),
                properties.requirePublishedForSearch(), properties.externalTransmissionAllowed(),
                properties.dataClassification(), properties.allowedProvider(), properties.privacyPolicyRequired(),
                properties.projectPolicies());
    }
}