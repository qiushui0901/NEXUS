package com.example.requirementrag.service;

import com.example.requirementrag.model.IngestResponse;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.retrieval.pipeline.RetrievalResultCache;
import com.example.requirementrag.model.KnowledgeEntry;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.observability.RagObservability;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    /**
     * 注入向量库、预处理器、分块器、可观测性组件与检索缓存（导入成功后失效缓存）。
     */
    public RequirementIngestionService(QdrantHybridStore store, TextPreprocessor preprocessor,
                                       RetrievalResultCache resultCache,
                                       ParentChildChunker chunker, RagObservability observability) {
        this.store = store;
        this.preprocessor = preprocessor;
        this.chunker = chunker;
        this.observability = observability;
        this.resultCache = resultCache;
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
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("没有可导入的知识条目");
        }

        List<ChunkRecord> chunks = new ArrayList<>();
        Set<String> seenParents = new HashSet<>();
        Set<String> seenChildren = new HashSet<>();
        for (KnowledgeEntry entry : entries) {
            String cleaned = observability.observe("text.clean", documentId, version, () -> preprocessor.clean(entry.text()));
            if (cleaned.isBlank()) {
                continue;
            }
            List<ParentChildChunker.ParentChunk> parents = observability.observe("parent_child.chunk", documentId, version,
                    () -> chunker.split(cleaned));
            observability.items("parent_child.chunk", "parents", parents.size());
            List<ChunkRecord> entryChunks = observability.observe("content.deduplicate", documentId, version,
                    () -> deduplicate(documentId, version, entry.source(), parents, seenParents, seenChildren));
            int rawChildren = parents.stream().mapToInt(parent -> parent.children().size()).sum();
            observability.items("content.deduplicate", "input", rawChildren);
            observability.items("content.deduplicate", "output", entryChunks.size());
            observability.items("content.deduplicate", "removed", rawChildren - entryChunks.size());
            chunks.addAll(entryChunks);
        }

        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档解析后没有有效文本");
        }

        if (collection != null && !collection.isBlank()) {
            observability.observe("qdrant.upsert", documentId, version,
                    () -> store.replaceVersion(collection, documentId, version, chunks));
        } else {
            observability.observe("qdrant.upsert", documentId, version,
                    () -> store.replaceVersion(documentId, version, chunks));
        }
        if (resultCache != null) {
            resultCache.invalidate(documentId, version);
        }
        observability.event("document_ingested");
        return new IngestResponse(documentId, version, chunks.size());
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
