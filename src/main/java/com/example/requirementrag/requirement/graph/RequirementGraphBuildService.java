package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.project.BusinessProjectCatalogService;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Entity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.EntityStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractedEntity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractedRelation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractionInput;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractionResult;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Relation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationType;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.example.requirementrag.requirement.graph.RequirementGraphModels.BuildRequest;

/** 从指定需求版本构建可审阅语义图；失败时不影响 Qdrant 需求主链路。 */
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

    @Autowired
    public RequirementGraphBuildService(SQLiteRequirementGraphStore store,
                                        RequirementGraphExtractionService extractionService,
                                        RequirementSnapshotRepository snapshots,
                                        QdrantHybridStore qdrantStore,
                                        ProjectRegistry projectRegistry,
                                        ObjectProvider<BusinessProjectCatalogService> businessProjects,
                                        RequirementGraphProperties properties) {
        this(store, extractionService, snapshots, qdrantStore, projectRegistry,
                businessProjects.getIfAvailable(), properties);
    }

    /** 供没有业务项目目录的兼容测试使用。 */
    public RequirementGraphBuildService(SQLiteRequirementGraphStore store,
                                        RequirementGraphExtractionService extractionService,
                                        RequirementSnapshotRepository snapshots,
                                        QdrantHybridStore qdrantStore,
                                        ProjectRegistry projectRegistry,
                                        RequirementGraphProperties properties) {
        this(store, extractionService, snapshots, qdrantStore, projectRegistry, (BusinessProjectCatalogService) null, properties);
    }

    private RequirementGraphBuildService(SQLiteRequirementGraphStore store,
                                         RequirementGraphExtractionService extractionService,
                                         RequirementSnapshotRepository snapshots,
                                         QdrantHybridStore qdrantStore,
                                         ProjectRegistry projectRegistry,
                                         BusinessProjectCatalogService businessProjects,
                                         RequirementGraphProperties properties) {
        this.store = store;
        this.extractionService = extractionService;
        this.snapshots = snapshots;
        this.qdrantStore = qdrantStore;
        this.projectRegistry = projectRegistry;
        this.businessProjects = businessProjects;
        this.properties = properties;
    }

    public GraphSnapshot build(BuildRequest request) {
        validate(request);
        String projectId = resolveProjectId(request.projectId());
        String collection = resolveCollection(projectId, request.collection());
        List<ChunkRecord> chunks = loadChunks(projectId, collection, request.documentId(), request.requirementVersion());
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("需求版本没有可构建语义图的父块");
        }
        String sourceRevision = sourceRevision(chunks);
        String snapshotId = "reqgraph:" + sha256(projectId + "|" + request.documentId() + "|"
                + request.requirementVersion() + "|" + sourceRevision + "|" + properties.extractionPromptVersion()).substring(0, 40);
        Instant now = Instant.now();
        GraphSnapshot building = new GraphSnapshot(snapshotId, projectId, request.documentId(),
                request.requirementVersion(), sourceRevision, extractionModel(),
                properties.extractionPromptVersion(), SnapshotStatus.BUILDING, 0, 0, now, now, null);
        store.saveSnapshot(building);
        try {
            BuildAccumulator accumulator = new BuildAccumulator(snapshotId, projectId, request.requirementVersion());
            for (ChunkRecord chunk : chunks) {
                String text = chunk.parentText() == null ? chunk.childText() : chunk.parentText();
                if (text == null || text.isBlank()) continue;
                String boundedText = text.length() <= properties.maxInputChars()
                        ? text : text.substring(0, properties.maxInputChars());
                ExtractionResult extracted = extractionService.extract(new ExtractionInput(
                        chunk.filename(), chunk.parentId(), chunk.parentOrder(), chunk.sectionPath(),
                        chunk.heading(), chunk.contentHash(), boundedText));
                accumulator.add(chunk, extracted);
            }
            List<Entity> entities = accumulator.entities();
            List<Relation> relations = accumulator.relations();
            if (entities.isEmpty()) {
                throw new IllegalArgumentException("需求语义图抽取未产生实体");
            }
            GraphSnapshot draft = new GraphSnapshot(snapshotId, projectId, request.documentId(),
                    request.requirementVersion(), sourceRevision, extractionModel(),
                    properties.extractionPromptVersion(), SnapshotStatus.REVIEW_REQUIRED,
                    entities.size(), relations.size(), now, Instant.now(), null);
            store.replaceDraft(draft, entities, relations);
            return store.requireSnapshot(snapshotId);
        } catch (RuntimeException exception) {
            store.updateStatus(snapshotId, SnapshotStatus.FAILED, null);
            throw exception;
        }
    }

    private List<ChunkRecord> loadChunks(String projectId, String collection, String documentId, String version) {
        try {
            java.util.Optional<Snapshot> snapshot = snapshots.materialize(snapshotNamespace(projectId), documentId, version);
            if (snapshot.isPresent() && !snapshot.get().entries().isEmpty()) {
                return snapshot.get().entries().stream().map(this::chunk).toList();
            }
        } catch (RuntimeException ignored) {
            // 快照不可用时回退到当前已发布 Qdrant payload；调用方仍可在 snapshot 上看到缺失事实。
        }
        return qdrantStore.scrollVersion(collection, documentId, version);
    }

    private ChunkRecord chunk(Entry entry) {
        String parentId = entry.entryId();
        String childId = entry.entryId() + "-child";
        return new ChunkRecord(childId, "", "", entry.filename(), parentId, entry.text(), entry.text(),
                entry.contentHash(), entry.parentOrder(), 0);
    }

    private String resolveProjectId(String projectId) {
        if (businessProjects == null) return projectId;
        return businessProjects.resolveProjectId(projectId);
    }

    private String snapshotNamespace(String projectId) {
        if (businessProjects == null) return projectId;
        return businessProjects.requireProject(projectId).requirementSnapshotNamespace();
    }

    private String resolveCollection(String projectId, String requested) {
        String configured = businessProjects != null
                ? businessProjects.requireProject(projectId).requirementCollection()
                : projectRegistry.resolveRequirementCollection(projectId);
        if (requested != null && !requested.isBlank() && !requested.trim().equals(configured)) {
            throw new IllegalArgumentException("需求语义图 collection 必须属于当前项目");
        }
        return configured;
    }

    private String extractionModel() {
        return extractionService.resolvedModel();
    }

    private String sourceRevision(List<ChunkRecord> chunks) {
        String value = chunks.stream()
                .sorted(Comparator.comparing(ChunkRecord::filename, Comparator.nullsFirst(String::compareTo))
                        .thenComparingInt(ChunkRecord::parentOrder)
                        .thenComparing(ChunkRecord::parentId, Comparator.nullsFirst(String::compareTo)))
                .map(chunk -> String.join("|", safe(chunk.filename()), safe(chunk.parentId()),
                        safe(chunk.contentHash()), Integer.toString(chunk.parentOrder())))
                .reduce("", (left, right) -> left + right + "\n");
        return sha256(value);
    }

    private void validate(BuildRequest request) {
        if (request == null || blank(request.projectId()) || blank(request.documentId())
                || blank(request.requirementVersion())) {
            throw new IllegalArgumentException("需求语义图构建请求不完整");
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private final class BuildAccumulator {
        private final String snapshotId;
        private final String projectId;
        private final String version;
        private final Map<String, EntityAccumulator> entities = new LinkedHashMap<>();
        private final Map<String, RelationAccumulator> relations = new LinkedHashMap<>();

        private BuildAccumulator(String snapshotId, String projectId, String version) {
            this.snapshotId = snapshotId;
            this.projectId = projectId;
            this.version = version;
        }

        private void add(ChunkRecord chunk, ExtractionResult extracted) {
            String evidenceId = RequirementGraphEvidence.id(projectId, version, chunk);
            Map<String, String> localToGlobal = new LinkedHashMap<>();
            for (ExtractedEntity item : extracted.entities()) {
                String canonical = canonical(item.name());
                String globalId = "entity:" + sha256(snapshotId + "|" + item.type() + "|" + canonical).substring(0, 40);
                localToGlobal.put(item.localId(), globalId);
                EntityAccumulator entity = entities.computeIfAbsent(globalId,
                        ignored -> new EntityAccumulator(globalId, snapshotId, item.type(), canonical, item.name().trim()));
                entity.add(item, evidenceId, chunk);
            }
            for (ExtractedRelation item : extracted.relations()) {
                String source = localToGlobal.get(item.sourceLocalId());
                String target = localToGlobal.get(item.targetLocalId());
                if (source == null || target == null) {
                    throw new IllegalArgumentException("需求语义图关系端点未解析");
                }
                String relationType = item.type();
                String relationId = "relation:" + sha256(snapshotId + "|" + source + "|"
                        + relationType + "|" + target).substring(0, 40);
                RelationAccumulator relation = relations.computeIfAbsent(relationId,
                        ignored -> new RelationAccumulator(relationId, snapshotId, source,
                                RelationType.valueOf(relationType), target, item.statement()));
                relation.add(item, evidenceId);
            }
        }

        private List<Entity> entities() {
            return entities.values().stream().map(EntityAccumulator::value).toList();
        }

        private List<Relation> relations() {
            return relations.values().stream().map(RelationAccumulator::value).toList();
        }
    }

    private static final class EntityAccumulator {
        private final String id;
        private final String snapshotId;
        private final String type;
        private final String canonicalName;
        private final String displayName;
        private final Set<String> aliases = new LinkedHashSet<>();
        private final Set<String> evidenceIds = new LinkedHashSet<>();
        private final Set<String> parentIds = new LinkedHashSet<>();
        private final Set<String> contentHashes = new LinkedHashSet<>();
        private String description = "";
        private double confidence;
        private int observations;

        private EntityAccumulator(String id, String snapshotId, String type,
                                  String canonicalName, String displayName) {
            this.id = id;
            this.snapshotId = snapshotId;
            this.type = type;
            this.canonicalName = canonicalName;
            this.displayName = displayName;
        }

        private void add(ExtractedEntity item, String evidenceId, ChunkRecord chunk) {
            aliases.addAll(item.aliases());
            evidenceIds.add(evidenceId);
            if (chunk.parentId() != null) parentIds.add(chunk.parentId());
            if (chunk.contentHash() != null) contentHashes.add(chunk.contentHash());
            if (description.isBlank() && item.description() != null) description = item.description();
            confidence = Math.max(confidence, item.confidence());
            observations++;
        }

        private Entity value() {
            return new Entity(id, snapshotId, RequirementGraphModels.EntityType.valueOf(type), canonicalName,
                    displayName, List.copyOf(aliases), description, List.copyOf(evidenceIds),
                    List.copyOf(parentIds), List.copyOf(contentHashes), confidence,
                    observations > 1 ? EntityStatus.NORMALIZED : EntityStatus.EXTRACTED);
        }
    }

    private static final class RelationAccumulator {
        private final String id;
        private final String snapshotId;
        private final String source;
        private final RelationType type;
        private final String target;
        private final String statement;
        private final Set<String> evidenceIds = new LinkedHashSet<>();
        private double confidence;

        private RelationAccumulator(String id, String snapshotId, String source, RelationType type,
                                    String target, String statement) {
            this.id = id;
            this.snapshotId = snapshotId;
            this.source = source;
            this.type = type;
            this.target = target;
            this.statement = statement;
        }

        private void add(ExtractedRelation item, String evidenceId) {
            evidenceIds.add(evidenceId);
            confidence = Math.max(confidence, item.confidence());
        }

        private Relation value() {
            return new Relation(id, snapshotId, source, type, target, statement,
                    List.copyOf(evidenceIds), confidence, RelationStatus.EXTRACTED, null, null);
        }
    }

    private static String canonical(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
