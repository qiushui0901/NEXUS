package com.example.requirementrag.service;

import com.example.requirementrag.model.IngestResponse;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.knowledge.management.KnowledgeIngestionTracker;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.EntityStatus;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.EventStatus;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.Stage;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.retrieval.pipeline.RetrievalResultCache;
import com.example.requirementrag.model.KnowledgeEntry;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.observability.RagObservability;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 需求文档导入服务：解析、分块、去重并写入向量库。
 */
@Service
public class RequirementIngestionService {

    private final QdrantHybridStore store;
    private final TextPreprocessor preprocessor;
    private final ParentChildChunker chunker;
    private final RagObservability observability;
    private final RetrievalResultCache resultCache;
    private final ProjectRegistry projectRegistry;
    private final KnowledgeIngestionTracker ingestionTracker;

    /**
     * 注入向量库、预处理器、分块器、可观测性组件与检索缓存（导入成功后失效缓存）。
     */
    public RequirementIngestionService(QdrantHybridStore store, TextPreprocessor preprocessor,
                                       RetrievalResultCache resultCache, ProjectRegistry projectRegistry,
                                       ParentChildChunker chunker, RagObservability observability) {
        this(store, preprocessor, resultCache, projectRegistry, chunker, observability,
                (KnowledgeIngestionTracker) null);
    }

    @Autowired
    public RequirementIngestionService(QdrantHybridStore store, TextPreprocessor preprocessor,
                                       RetrievalResultCache resultCache, ProjectRegistry projectRegistry,
                                       ParentChildChunker chunker, RagObservability observability,
                                       ObjectProvider<KnowledgeIngestionTracker> ingestionTracker) {
        this(store, preprocessor, resultCache, projectRegistry, chunker, observability,
                ingestionTracker.getIfAvailable());
    }

    private RequirementIngestionService(QdrantHybridStore store, TextPreprocessor preprocessor,
                                        RetrievalResultCache resultCache, ProjectRegistry projectRegistry,
                                        ParentChildChunker chunker, RagObservability observability,
                                        KnowledgeIngestionTracker ingestionTracker) {
        this.store = store;
        this.preprocessor = preprocessor;
        this.chunker = chunker;
        this.observability = observability;
        this.resultCache = resultCache;
        this.projectRegistry = projectRegistry;
        this.ingestionTracker = ingestionTracker;
    }

    /**
     * 上传 multipart 文件并导入为指定版本的向量分块。使用默认 collection。
     */
    public IngestResponse ingest(MultipartFile file, String version, String documentId) throws IOException {
        return ingest(null, file, version, documentId);
    }

    /**
     * 上传 multipart 文件并导入为指定版本的向量分块。
     *
     * @param collection 目标 collection，可空（使用默认 collection）
     * @param file       上传的需求文件，由 Tika 解析为纯文本
     * @param version    需求版本
     * @param documentId 文档 ID，空时自动生成 UUID
     * @return 导入结果（文档 ID、版本与分块数量）
     * @throws IOException 文件读取或解析失败时抛出
     */
    public IngestResponse ingest(String collection, MultipartFile file, String version, String documentId) throws IOException {
        String id = StringUtils.hasText(documentId) ? documentId : UUID.randomUUID().toString();
        String filename = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "document";
        return ingestEntries(collection, id, version, List.of(new KnowledgeEntry(filename, readMultipart(file, id, version, filename))));
    }

    /**
     * 批量导入知识条目：清洗、分块、去重后替换指定版本的全部分块。使用默认 collection。
     */
    public IngestResponse ingestEntries(String documentId, String version, List<KnowledgeEntry> entries) {
        return ingestEntries(null, documentId, version, entries);
    }

    /**
     * 批量导入知识条目：清洗、分块、去重后替换指定版本的全部分块。
     *
     * @param collection 目标 collection，可空（使用默认 collection）
     * @param documentId 文档 ID
     * @param version    需求版本
     * @param entries    知识条目（文件名 + 文本）；为空时抛出异常
     * @return 导入结果（文档 ID、版本与分块数量）
     * @throws IllegalArgumentException 没有可导入条目或解析后无有效文本时抛出
     */
    public IngestResponse ingestEntries(String collection, String documentId, String version, List<KnowledgeEntry> entries) {
        return ingestEntries(collection, documentId, version, entries, KnowledgeIngestionTracker.Context.disabled());
    }

    /** 批量导入并把逐文档、逐分块阶段写入知识管理状态目录。 */
    public IngestResponse ingestEntries(String collection, String documentId, String version,
                                        List<KnowledgeEntry> entries,
                                        KnowledgeIngestionTracker.Context trackingContext) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("没有可导入的知识条目");
        }

        boolean tracking = ingestionTracker != null && trackingContext != null && trackingContext.enabled();
        List<ChunkRecord> chunks = new ArrayList<>();
        Map<String, List<ChunkRecord>> chunksBySource = new LinkedHashMap<>();
        Map<String, String> sourceHashes = new LinkedHashMap<>();
        Set<String> seenParents = new HashSet<>();
        Set<String> seenChildren = new HashSet<>();
        for (KnowledgeEntry entry : entries) {
            String source = entry.source();
            String sourceHash = Hashing.sha256(entry.text());
            sourceHashes.put(source, sourceHash);
            trackDocument(trackingContext, source, sourceHash, EntityStatus.RUNNING, Stage.CLEAN, 0, 0, null);
            trackEvent(trackingContext, source, Stage.CLEAN, EventStatus.RUNNING, 1, 0, 0, null);
            String cleaned;
            try {
                cleaned = observability.observe("text.clean", documentId, version,
                        () -> preprocessor.clean(entry.text()));
            } catch (RuntimeException exception) {
                trackFailure(trackingContext, source, sourceHash, Stage.CLEAN, List.of(), exception);
                throw exception;
            }
            if (cleaned.isBlank()) {
                trackEvent(trackingContext, source, Stage.CLEAN, EventStatus.SKIPPED, 1, 0, 1, null);
                trackDocument(trackingContext, source, sourceHash, EntityStatus.EXCLUDED,
                        Stage.CLEAN, 0, 1, null);
                continue;
            }
            trackEvent(trackingContext, source, Stage.CLEAN, EventStatus.SUCCEEDED, 1, 1, 0, null);
            trackEvent(trackingContext, source, Stage.CHUNK, EventStatus.RUNNING, 1, 0, 0, null);
            List<ParentChildChunker.ParentChunk> parents;
            try {
                parents = observability.observe("parent_child.chunk", documentId, version,
                        () -> chunker.split(cleaned));
            } catch (RuntimeException exception) {
                trackFailure(trackingContext, source, sourceHash, Stage.CHUNK, List.of(), exception);
                throw exception;
            }
            observability.items("parent_child.chunk", "parents", parents.size());
            int rawChildren = parents.stream().mapToInt(parent -> parent.children().size()).sum();
            trackEvent(trackingContext, source, Stage.CHUNK, EventStatus.SUCCEEDED,
                    1, rawChildren, 0, null);
            trackEvent(trackingContext, source, Stage.DEDUPLICATE, EventStatus.RUNNING,
                    rawChildren, 0, 0, null);
            List<ChunkRecord> entryChunks = observability.observe("content.deduplicate", documentId, version,
                    () -> deduplicate(documentId, version, source, parents, seenParents, seenChildren));
            observability.items("content.deduplicate", "input", rawChildren);
            observability.items("content.deduplicate", "output", entryChunks.size());
            observability.items("content.deduplicate", "removed", rawChildren - entryChunks.size());
            trackEvent(trackingContext, source, Stage.DEDUPLICATE, EventStatus.SUCCEEDED,
                    rawChildren, entryChunks.size(), rawChildren - entryChunks.size(), null);
            trackDocument(trackingContext, source, sourceHash, EntityStatus.CHUNKED,
                    Stage.DEDUPLICATE, entryChunks.size(), rawChildren - entryChunks.size(), null);
            trackChunks(trackingContext, source, entryChunks, EntityStatus.CHUNKED,
                    Stage.DEDUPLICATE, false, false, false, null);
            chunksBySource.put(source, entryChunks);
            chunks.addAll(entryChunks);
        }

        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档解析后没有有效文本");
        }

        AtomicReference<Stage> activeStage = new AtomicReference<>(Stage.EMBED);
        QdrantHybridStore.ProgressListener listener = (replaceStage, completed, total) -> {
            Stage stage = switch (replaceStage) {
                case EMBED -> Stage.EMBED;
                case INDEX -> Stage.INDEX;
                case VERIFY -> Stage.VERIFY;
                case PUBLISH -> Stage.PUBLISH;
            };
            activeStage.set(stage);
            reportQdrantProgress(trackingContext, chunks, sourceHashes, stage, completed, total);
        };
        try {
            if (collection != null && !collection.isBlank()) {
                observability.observe("qdrant.upsert", documentId, version, () -> {
                    if (tracking) store.replaceVersion(collection, documentId, version, chunks, listener);
                    else store.replaceVersion(collection, documentId, version, chunks);
                });
            } else {
                observability.observe("qdrant.upsert", documentId, version, () -> {
                    if (tracking) store.replaceVersion(documentId, version, chunks, listener);
                    else store.replaceVersion(documentId, version, chunks);
                });
            }
        } catch (RuntimeException exception) {
            Stage failedStage = activeStage.get();
            for (Map.Entry<String, List<ChunkRecord>> source : chunksBySource.entrySet()) {
                trackFailure(trackingContext, source.getKey(), sourceHashes.get(source.getKey()),
                        failedStage, source.getValue(), exception);
            }
            if (tracking) ingestionTracker.chunkProgress(trackingContext, failedStage,
                    chunks.size(), 0, chunks.size());
            throw exception;
        }
        if (resultCache != null) {
            String cacheProjectId = projectRegistry == null ? null
                    : projectRegistry.findProjectIdByRequirementCollection(collection).orElse(null);
            if (cacheProjectId != null) {
                resultCache.invalidate(cacheProjectId, documentId, version);
            } else {
                resultCache.invalidateAll(documentId, version);
            }
        }
        observability.event("document_ingested");
        return new IngestResponse(documentId, version, chunks.size());
    }

    private void reportQdrantProgress(KnowledgeIngestionTracker.Context context, List<ChunkRecord> chunks,
                                      Map<String, String> sourceHashes, Stage stage,
                                      int completed, int total) {
        if (!trackingEnabled(context)) return;
        int bounded = Math.max(0, Math.min(completed, chunks.size()));
        Map<String, List<ChunkRecord>> completedBySource = new LinkedHashMap<>();
        for (ChunkRecord chunk : chunks.subList(0, bounded)) {
            completedBySource.computeIfAbsent(chunk.filename(), ignored -> new ArrayList<>()).add(chunk);
        }
        EntityStatus status = switch (stage) {
            case EMBED -> EntityStatus.EMBEDDING;
            case INDEX -> EntityStatus.INDEXING;
            case VERIFY, PUBLISH -> EntityStatus.READY;
            default -> EntityStatus.RUNNING;
        };
        boolean denseReady = stage.ordinal() >= Stage.EMBED.ordinal();
        boolean sparseReady = denseReady;
        boolean verified = stage.ordinal() >= Stage.VERIFY.ordinal();
        for (Map.Entry<String, List<ChunkRecord>> source : completedBySource.entrySet()) {
            trackChunks(context, source.getKey(), source.getValue(), status, stage,
                    denseReady, sparseReady, verified, null);
            trackDocument(context, source.getKey(), sourceHashes.get(source.getKey()), status,
                    stage, source.getValue().size(), 0, null);
        }
        ingestionTracker.chunkProgress(context, stage, total, verified ? completed : 0, 0);
        EventStatus eventStatus = completed >= total ? EventStatus.SUCCEEDED : EventStatus.RUNNING;
        ingestionTracker.event(context, "RUN", context.runId(), stage, eventStatus,
                total, completed, 0, null);
    }

    private void trackFailure(KnowledgeIngestionTracker.Context context, String source, String sourceHash,
                              Stage stage, List<ChunkRecord> chunks, RuntimeException exception) {
        trackDocument(context, source, sourceHash, EntityStatus.FAILED, stage, chunks.size(), 0, exception);
        trackChunks(context, source, chunks, EntityStatus.FAILED, stage,
                stage.ordinal() >= Stage.EMBED.ordinal(), stage.ordinal() >= Stage.EMBED.ordinal(),
                false, exception);
        trackEvent(context, source, stage, EventStatus.FAILED, chunks.size(), 0, chunks.size(), exception);
    }

    private void trackDocument(KnowledgeIngestionTracker.Context context, String source, String sourceHash,
                               EntityStatus status, Stage stage, int chunks, int excluded, Throwable error) {
        if (trackingEnabled(context)) {
            ingestionTracker.document(context, source, sourceHash, status, stage, chunks, excluded, error);
        }
    }

    private void trackChunks(KnowledgeIngestionTracker.Context context, String source, List<ChunkRecord> chunks,
                             EntityStatus status, Stage stage, boolean denseReady, boolean sparseReady,
                             boolean verified, Throwable error) {
        if (trackingEnabled(context)) {
            ingestionTracker.chunks(context, source, chunks, status, stage,
                    denseReady, sparseReady, verified, error);
        }
    }

    private void trackEvent(KnowledgeIngestionTracker.Context context, String source, Stage stage,
                            EventStatus status, int input, int output, int excluded, Throwable error) {
        if (trackingEnabled(context)) {
            ingestionTracker.event(context, "DOCUMENT", ingestionTracker.documentId(context, source),
                    stage, status, input, output, excluded, error);
        }
    }

    private boolean trackingEnabled(KnowledgeIngestionTracker.Context context) {
        return ingestionTracker != null && context != null && context.enabled();
    }

    /** 使用 Tika 解析上传文件为纯文本。 */
    private String readMultipart(MultipartFile file, String id, String version, String filename) throws IOException {
        byte[] fileBytes = file.getBytes();
        return observability.observe("document.parse", id, version, () -> {
            var resource = new NamedByteArrayResource(fileBytes, filename);
            return new TikaDocumentReader(resource).read().stream().map(Document::getText)
                    .reduce("", (left, right) -> left + "\n" + right);
        });
    }

    /** 按内容哈希去重父块与子块，生成 ChunkRecord 列表。 */
    private List<ChunkRecord> deduplicate(String id, String version, String filename,
                                          List<ParentChildChunker.ParentChunk> parents,
                                          Set<String> seenParents, Set<String> seenChildren) {
        List<ChunkRecord> chunks = new ArrayList<>();
        for (ParentChildChunker.ParentChunk parent : parents) {
            String parentHash = Hashing.sha256(parent.text());
            if (!seenParents.add(parentHash)) continue;
            String parentId = Hashing.uuid(id + ":" + version + ":parent:" + parentHash);
            for (int childIndex = 0; childIndex < parent.children().size(); childIndex++) {
                String child = parent.children().get(childIndex);
                String childHash = Hashing.sha256(child);
                if (!seenChildren.add(childHash)) continue;
                chunks.add(new ChunkRecord(Hashing.uuid(id + ":" + version + ":child:" + childHash), id, version,
                        filename, parentId, parent.text(), child, childHash, parent.order(), childIndex));
            }
        }
        return chunks;
    }

    /** 带文件名的 ByteArrayResource，供 Tika 识别文件类型。 */
    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        /** 构造带原始文件名的字节资源。 */
        private NamedByteArrayResource(byte[] bytes, String filename) {
            super(bytes);
            this.filename = filename;
        }

        /** 返回原始文件名。 */
        @Override
        public String getFilename() {
            return filename;
        }
    }
}
