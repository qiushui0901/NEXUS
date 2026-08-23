package com.example.requirementrag.retrieval;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.ScoredChunk;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Qdrant 混合向量存储：支持稠密+稀疏向量写入、RRF 融合检索与版本管理。
 * 所有公开方法接受 collection 参数，支持多项目按不同 collection 隔离。
 */
@Repository
public class QdrantHybridStore {

    private final RestClient client;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingBatcher embeddingBatcher;
    private final SparseVectorizer sparseVectorizer;
    private final RagProperties properties;
    private final Set<String> initializedCollections = ConcurrentHashMap.newKeySet();
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(QdrantHybridStore.class);

    /** 注入 Qdrant 客户端、嵌入模型、稀疏向量化器与配置。 */
    public QdrantHybridStore(RestClient qdrantRestClient,
                             EmbeddingModel embeddingModel, EmbeddingBatcher embeddingBatcher,
                             SparseVectorizer sparseVectorizer, RagProperties properties) {
        this.client = qdrantRestClient;
        this.embeddingModel = embeddingModel;
        this.embeddingBatcher = embeddingBatcher;
        this.sparseVectorizer = sparseVectorizer;
        this.properties = properties;
    }

    /**
     * 替换指定文档版本的全部分块：先删后批量写入。使用默认 collection。
     *
     * @param documentId 文档 ID
     * @param version    文档版本号
     * @param chunks     新版本的全部分块
     */
    public void replaceVersion(String documentId, String version, List<ChunkRecord> chunks) {
        replaceVersion(collection(), documentId, version, chunks, ProgressListener.noop());
    }

    /** 使用默认 collection 替换版本，并报告嵌入、写入、校验和发布进度。 */
    public void replaceVersion(String documentId, String version, List<ChunkRecord> chunks,
                               ProgressListener progressListener) {
        replaceVersion(collection(), documentId, version, chunks, progressListener);
    }

    /**
     * 替换指定文档版本的全部分块：先写入新点（幂等 upsert）、校验可读后，
     * 再删除仅存在于旧版本的过期点。任一步失败时保留旧点，不执行清理，
     * 避免删除成功后写入失败导致线上版本变空或半成品。
     *
     * @param collection Qdrant collection 名称
     * @param documentId 文档 ID
     * @param version    文档版本号
     * @param chunks     新版本的全部分块
     */
    public void replaceVersion(String collection, String documentId, String version, List<ChunkRecord> chunks) {
        replaceVersion(collection, documentId, version, chunks, ProgressListener.noop());
    }

    /**
     * 替换指定文档版本并报告批次进度。进度监听器属于旁路能力，
     * 监听器异常不会中断 Qdrant 发布流程。
     */
    public void replaceVersion(String collection, String documentId, String version, List<ChunkRecord> chunks,
                               ProgressListener progressListener) {
        ensureCollection(collection);
        ProgressListener listener = progressListener == null ? ProgressListener.noop() : progressListener;
        if (chunks == null || chunks.isEmpty()) {
            deleteVersion(collection, documentId, version);
            notifyProgress(listener, ReplaceStage.PUBLISH, 0, 0);
            return;
        }
        List<List<Map<String, Object>>> pointBatches = buildPointBatches(chunks, 64, listener);
        java.util.Set<String> oldIds = collectPointIds(collection, documentId, version);
        writePointBatches(collection, pointBatches, chunks.size(), listener);
        java.util.Set<String> newIds = new java.util.LinkedHashSet<>();
        for (ChunkRecord chunk : chunks) {
            newIds.add(chunk.id());
        }
        verifyVersion(collection, documentId, version, newIds);
        notifyProgress(listener, ReplaceStage.VERIFY, newIds.size(), newIds.size());
        java.util.Set<String> staleIds = new java.util.HashSet<>(oldIds);
        staleIds.removeAll(newIds);
        if (!staleIds.isEmpty()) {
            deletePoints(collection, staleIds);
        }
        notifyProgress(listener, ReplaceStage.PUBLISH, newIds.size(), newIds.size());
    }

    /**
     * 局部替换指定文档版本中的来源文件。
     *
     * <p>只为 {@code replacedSources} 中的来源写入新向量并删除旧 point，
     * 其他来源的 point 原样保留。这是 {@code ingestIncremental} 的发布路径，
     * 避免“只变化一个文件却清空整个版本”的整版替换问题。</p>
     */
    public void replaceSources(String collection, String documentId, String version,
                               List<ChunkRecord> changedChunks, Set<String> replacedSources,
                               ProgressListener progressListener) {
        ensureCollection(collection);
        ProgressListener listener = progressListener == null ? ProgressListener.noop() : progressListener;
        Set<String> sources = replacedSources == null ? Set.of() : Set.copyOf(replacedSources);
        if (sources.isEmpty()) {
            notifyProgress(listener, ReplaceStage.PUBLISH, 0, 0);
            return;
        }

        List<ChunkRecord> existing = scrollVersion(collection, documentId, version);
        Set<String> staleIds = new java.util.LinkedHashSet<>();
        Set<String> preservedIds = new java.util.LinkedHashSet<>();
        for (ChunkRecord chunk : existing) {
            if (sources.contains(chunk.filename())) {
                staleIds.add(chunk.id());
            } else {
                preservedIds.add(chunk.id());
            }
        }

        List<ChunkRecord> replacement = changedChunks == null ? List.of() : List.copyOf(changedChunks);
        Set<String> replacementIds = new java.util.LinkedHashSet<>();
        if (!replacement.isEmpty()) {
            List<List<Map<String, Object>>> batches = buildPointBatches(replacement, 64, listener);
            writePointBatches(collection, batches, replacement.size(), listener);
            for (ChunkRecord chunk : replacement) {
                replacementIds.add(chunk.id());
            }
            verifyVersion(collection, documentId, version, replacementIds);
            notifyProgress(listener, ReplaceStage.VERIFY, replacementIds.size(), replacementIds.size());
        }

        // 同一内容可能在来源变更后复用旧 chunk ID；不能把刚 upsert 的新 point 再删除。
        staleIds.removeAll(replacementIds);
        if (!staleIds.isEmpty()) {
            deletePoints(collection, staleIds);
        }

        Set<String> expectedIds = new java.util.LinkedHashSet<>(preservedIds);
        expectedIds.addAll(replacementIds);
        if (!expectedIds.isEmpty()) {
            verifyVersion(collection, documentId, version, expectedIds);
        }
        notifyProgress(listener, ReplaceStage.PUBLISH, expectedIds.size(), expectedIds.size());
    }

    /** 使用默认 collection 局部替换指定文档版本中的来源文件。 */
    public void replaceSources(String documentId, String version, List<ChunkRecord> changedChunks,
                               Set<String> replacedSources, ProgressListener progressListener) {
        replaceSources(collection(), documentId, version, changedChunks, replacedSources, progressListener);
    }

    /** 滚动读取指定文档版本的全部 point ID。 */
    private java.util.Set<String> collectPointIds(String collection, String documentId, String version) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        Object offset = null;
        do {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("filter", filter(documentId, version));
            request.put("limit", 256);
            request.put("with_payload", false);
            if (offset != null) request.put("offset", offset);
            Map<String, Object> response = client.post().uri("/collections/{collection}/points/scroll", collection)
                    .contentType(MediaType.APPLICATION_JSON).body(request).retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            Map<String, Object> result = map(response == null ? null : response.get("result"));
            for (Object point : list(result.get("points"))) {
                Map<String, Object> p = map(point);
                Object id = p.get("id");
                if (id != null) ids.add(String.valueOf(id));
            }
            offset = result.get("next_page_offset");
            if (offset == null) break;
        } while (true);
        return ids;
    }

    /** 校验新写入的 point ID 全部可读且数量一致；不一致时抛异常（旧点保留）。 */
    private void verifyVersion(String collection, String documentId, String version, java.util.Set<String> newIds) {
        Map<String, Object> request = new LinkedHashMap<>();
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("must", java.util.List.of(
                Map.of("key", "documentId", "match", Map.of("value", documentId)),
                Map.of("key", "version", "match", Map.of("value", version)),
                // Qdrant >= 1.13 rejects "$point_id" + match.any; use has_id instead.
                Map.of("has_id", new java.util.ArrayList<>(newIds))));
        request.put("filter", filter);
        request.put("limit", Math.max(256, newIds.size()));
        request.put("with_payload", false);
        Map<String, Object> response = client.post().uri("/collections/{collection}/points/scroll", collection)
                .contentType(MediaType.APPLICATION_JSON).body(request).retrieve()
                .body(new ParameterizedTypeReference<>() {});
        Map<String, Object> result = map(response == null ? null : response.get("result"));
        int verified = list(result.get("points")).size();
        if (verified != newIds.size()) {
            throw new IllegalStateException("索引写入校验失败: 期望 " + newIds.size() + " 个 point, 实际可读 " + verified);
        }
    }

    /** 按 point ID 批量删除（wait=true）。 */
    private void deletePoints(String collection, java.util.Set<String> ids) {
        client.post().uri("/collections/{collection}/points/delete?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("points", new java.util.ArrayList<>(ids)))
                .retrieve().toBodilessEntity();
    }


    /** 将分块按指定批量大小分组，供分批次写入。 */
    private List<List<Map<String, Object>>> buildPointBatches(List<ChunkRecord> chunks, int batchSize) {
        return buildPointBatches(chunks, batchSize, ProgressListener.noop());
    }

    /** 构建 point 批次，并在每批稠密/稀疏向量完成后报告进度。 */
    private List<List<Map<String, Object>>> buildPointBatches(List<ChunkRecord> chunks, int batchSize,
                                                              ProgressListener listener) {
        List<List<Map<String, Object>>> batches = new ArrayList<>();
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, chunks.size());
            batches.add(buildPoints(chunks.subList(start, end)));
            notifyProgress(listener, ReplaceStage.EMBED, end, chunks.size());
        }
        return batches;
    }

    /** 逐批 PUT 写入 Qdrant 点，wait=true 等待持久化完成。 */
    private void writePointBatches(String collection, List<List<Map<String, Object>>> batches) {
        int total = batches.stream().mapToInt(List::size).sum();
        writePointBatches(collection, batches, total, ProgressListener.noop());
    }

    /** 逐批写入并在 wait=true 成功返回后报告已持久化点数。 */
    private void writePointBatches(String collection, List<List<Map<String, Object>>> batches,
                                   int total, ProgressListener listener) {
        int completed = 0;
        for (List<Map<String, Object>> points : batches) {
            client.put().uri("/collections/{collection}/points?wait=true", collection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("points", points))
                    .retrieve().toBodilessEntity();
            completed += points.size();
            notifyProgress(listener, ReplaceStage.INDEX, completed, total);
        }
    }

    private void notifyProgress(ProgressListener listener, ReplaceStage stage, int completed, int total) {
        try {
            listener.onProgress(stage, completed, total);
        } catch (RuntimeException ignored) {
            // 管理状态是旁路能力，不能覆盖真实的索引发布结果。
        }
    }

    /** 安全发布过程的可观察阶段。 */
    public enum ReplaceStage { EMBED, INDEX, VERIFY, PUBLISH }

    /** Qdrant 版本替换进度监听器。 */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(ReplaceStage stage, int completed, int total);

        static ProgressListener noop() {
            return (stage, completed, total) -> { };
        }
    }

    /** 为分块批次构建 Qdrant 点结构（稠密+稀疏向量与 payload）。 */
    private List<Map<String, Object>> buildPoints(List<ChunkRecord> chunks) {
        List<String> childTexts = chunks.stream().map(ChunkRecord::childText).toList();
        List<float[]> denseVectors = embeddingBatcher.embedAll(childTexts);
        List<Map<String, Object>> points = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            ChunkRecord chunk = chunks.get(index);
            SparseVectorizer.SparseVector sparse = sparseVectorizer.vectorize(chunk.childText());
            points.add(Map.of(
                    "id", chunk.id(),
                    "vector", Map.of("dense", denseVectors.get(index),
                            "sparse", Map.of("indices", sparse.indices(), "values", sparse.values())),
                    "payload", Map.ofEntries(
                            Map.entry("documentId", chunk.documentId()),
                            Map.entry("version", chunk.version()),
                            Map.entry("filename", chunk.filename()),
                            Map.entry("parentId", chunk.parentId()),
                            Map.entry("parentText", chunk.parentText()),
                            Map.entry("childText", chunk.childText()),
                            Map.entry("contentHash", chunk.contentHash()),
                            Map.entry("parentOrder", chunk.parentOrder()),
                            Map.entry("childOrder", chunk.childOrder()),
                            Map.entry("sectionPath", chunk.sectionPath()),
                            Map.entry("heading", chunk.heading()),
                            Map.entry("requirementId", chunk.requirementId()),
                            Map.entry("module", chunk.module()),
                            Map.entry("acceptanceCriteria", chunk.acceptanceCriteria()),
                            Map.entry("sourceType", chunk.sourceType() == null ? "REQUIREMENT" : chunk.sourceType()),
                            Map.entry("documentVersionId", nullSafe(chunk.documentVersionId())),
                            Map.entry("authority", nullSafe(chunk.authority())),
                            Map.entry("status", nullSafe(chunk.status())),
                            Map.entry("evidenceId", nullSafe(chunk.evidenceId())),
                            Map.entry("factKey", nullSafe(chunk.factKey())))));
        }
        return points;
    }

    /**
     * 执行稠密+稀疏 prefetch 与 RRF 融合的混合检索。使用默认 collection。
     *
     * @param query      查询文本
     * @param documentId 限定检索的文档 ID
     * @param version    限定检索的文档版本号
     * @return 融合排序后的分块列表，无结果时为空列表
     */
    public List<ChunkRecord> hybridSearch(String query, String documentId, String version) {
        return hybridSearch(collection(), query, documentId, version);
    }

    /** 执行稠密+稀疏 prefetch 与 RRF 融合的混合检索。 */
    public List<ChunkRecord> hybridSearch(String collection, String query, String documentId, String version) {
        ensureCollection(collection);
        float[] dense = embeddingBatcher.embedAll(List.of(query)).get(0);
        SparseVectorizer.SparseVector sparse = sparseVectorizer.vectorize(query);
        RagProperties.Retrieval cfg = properties.retrieval();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prefetch", List.of(
                Map.of("query", dense, "using", "dense", "limit", cfg.denseTopK(), "filter", filter(documentId, version)),
                Map.of("query", Map.of("indices", sparse.indices(), "values", sparse.values()), "using", "sparse", "limit", cfg.sparseTopK(), "filter", filter(documentId, version))));
        body.put("query", Map.of("fusion", "rrf"));
        body.put("limit", cfg.hybridTopK());
        body.put("with_payload", true);
        Map<String, Object> response = client.post().uri("/collections/{collection}/points/query", collection)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return extractPoints(response);
    }

    /** 执行混合检索并按多源类型过滤：空集合表示不过滤。 */
    public List<ChunkRecord> hybridSearch(String collection, String query, String documentId, String version,
                                          Set<String> sourceTypes) {
        ensureCollection(collection);
        float[] dense = embeddingBatcher.embedAll(List.of(query)).get(0);
        SparseVectorizer.SparseVector sparse = sparseVectorizer.vectorize(query);
        RagProperties.Retrieval cfg = properties.retrieval();
        Map<String, Object> filter = filter(documentId, version, sourceTypes);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prefetch", List.of(
                Map.of("query", dense, "using", "dense", "limit", cfg.denseTopK(), "filter", filter),
                Map.of("query", Map.of("indices", sparse.indices(), "values", sparse.values()), "using", "sparse", "limit", cfg.sparseTopK(), "filter", filter)));
        body.put("query", Map.of("fusion", "rrf"));
        body.put("limit", cfg.hybridTopK());
        body.put("with_payload", true);
        Map<String, Object> response = client.post().uri("/collections/{collection}/points/query", collection)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return extractPoints(response);
    }

    /**
     * 执行混合检索并返回带 Qdrant 原生融合分数的结果。
     *
     * @param collection Qdrant collection 名称
     * @param query      查询文本
     * @param documentId 限定检索的文档 ID
     * @param version    限定检索的文档版本号
     * @return 带分数的分块列表，无结果时为空列表
     */
    public List<ScoredChunk> hybridSearchWithScores(String collection, String query, String documentId, String version) {
        ensureCollection(collection);
        float[] dense = embeddingBatcher.embedAll(List.of(query)).get(0);
        SparseVectorizer.SparseVector sparse = sparseVectorizer.vectorize(query);
        RagProperties.Retrieval cfg = properties.retrieval();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prefetch", List.of(
                Map.of("query", dense, "using", "dense", "limit", cfg.denseTopK(), "filter", filter(documentId, version)),
                Map.of("query", Map.of("indices", sparse.indices(), "values", sparse.values()), "using", "sparse", "limit", cfg.sparseTopK(), "filter", filter(documentId, version))));
        body.put("query", Map.of("fusion", "rrf"));
        body.put("limit", cfg.hybridTopK());
        body.put("with_payload", true);
        Map<String, Object> response = client.post().uri("/collections/{collection}/points/query", collection)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return extractScoredPoints(response);
    }

    /** 执行混合检索（含分数）并按多源类型过滤：空集合表示不过滤。 */
    public List<ScoredChunk> hybridSearchWithScores(String collection, String query, String documentId, String version,
                                                    Set<String> sourceTypes) {
        ensureCollection(collection);
        float[] dense = embeddingBatcher.embedAll(List.of(query)).get(0);
        SparseVectorizer.SparseVector sparse = sparseVectorizer.vectorize(query);
        RagProperties.Retrieval cfg = properties.retrieval();
        Map<String, Object> filter = filter(documentId, version, sourceTypes);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prefetch", List.of(
                Map.of("query", dense, "using", "dense", "limit", cfg.denseTopK(), "filter", filter),
                Map.of("query", Map.of("indices", sparse.indices(), "values", sparse.values()), "using", "sparse", "limit", cfg.sparseTopK(), "filter", filter)));
        body.put("query", Map.of("fusion", "rrf"));
        body.put("limit", cfg.hybridTopK());
        body.put("with_payload", true);
        Map<String, Object> response = client.post().uri("/collections/{collection}/points/query", collection)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return extractScoredPoints(response);
    }

    /** 从查询响应中提取带分数的分块列表。 */
    private List<ScoredChunk> extractScoredPoints(Map<String, Object> response) {
        Object result = response == null ? null : response.get("result");
        Map<String, Object> resultMap = map(result);
        Object points = resultMap.isEmpty() ? result : resultMap.get("points");
        return list(points).stream().map(this::toScoredRecord).toList();
    }

    /** 将单个 Qdrant 点转换为带分数的记录，缺失 score 时按 0 处理。 */
    private ScoredChunk toScoredRecord(Object raw) {
        Map<String, Object> point = map(raw);
        double score = point.containsKey("score") ? ((Number) point.get("score")).doubleValue() : 0.0;
        return new ScoredChunk(toRecord(raw), score);
    }

    /**
     * 统计指定文档版本的分块数量。使用默认 collection。
     *
     * @param documentId 文档 ID
     * @param version    文档版本号
     * @return 匹配该文档版本的点数量
     */
    public long countVersion(String documentId, String version) {
        return countVersion(collection(), documentId, version);
    }

    /** 统计指定文档版本的分块数量。 */
    public long countVersion(String collection, String documentId, String version) {
        ensureCollection(collection);
        return countVersionWithoutInitialization(collection, documentId, version);
    }

    /**
     * 监控专用的快速只读计数，不创建 collection、不执行可用性重试。
     * 调用方负责把不可用异常转换为降级状态。
     */
    public long countVersionIfAvailable(String collection, String documentId, String version) {
        return countVersionWithoutInitialization(collection, documentId, version);
    }

    private long countVersionWithoutInitialization(String collection, String documentId, String version) {
        Map<String, Object> response = client.post().uri("/collections/{collection}/points/count", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("filter", filter(documentId, version)))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        Map<String, Object> result = map(response == null ? null : response.get("result"));
        return ((Number) result.getOrDefault("count", 0)).longValue();
    }

    /**
     * 知识管理页兜底用：快速读取指定 collection 的真实分块总数。
     * 不创建 collection、不执行可用性重试；Qdrant 不存在或不可用时返回 0。
     */
    public long countPointsIfAvailable(String collection) {
        try {
            Map<String, Object> response = client.get().uri("/collections/{collection}", collection)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            Map<String, Object> result = map(response == null ? null : response.get("result"));
            return ((Number) result.getOrDefault("points_count", 0)).longValue();
        } catch (HttpClientErrorException.NotFound exception) {
            return 0L;
        } catch (ResourceAccessException exception) {
            return 0L;
        } catch (RuntimeException exception) {
            return 0L;
        }
    }

    /**
     * 分页滚动获取指定文档版本的全部 payload。使用默认 collection。
     *
     * @param documentId 文档 ID
     * @param version    文档版本号
     * @return 该文档版本的全部分块
     */
    public List<ChunkRecord> scrollVersion(String documentId, String version) {
        return scrollVersion(collection(), documentId, version);
    }

    /** 分页滚动获取指定文档版本的全部 payload。 */
    public List<ChunkRecord> scrollVersion(String collection, String documentId, String version) {
        ensureCollection(collection);
        List<ChunkRecord> result = new ArrayList<>();
        Object offset = null;
        do {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("filter", filter(documentId, version));
            request.put("limit", 256);
            request.put("with_payload", true);
            request.put("with_vector", false);
            if (offset != null) request.put("offset", offset);
            Map<String, Object> response = client.post().uri("/collections/{collection}/points/scroll", collection)
                    .contentType(MediaType.APPLICATION_JSON).body(request).retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            Map<String, Object> page = map(response == null ? null : response.get("result"));
            result.addAll(toRecords(list(page.get("points"))));
            offset = page.get("next_page_offset");
        } while (offset != null);
        return result;
    }

    /** payload-only 批量更新：只更新指定点 payload，不重算向量，用于发布目录/字段回填。 */
    public void setPayload(String collection, Map<String, Map<String, Object>> payloadById) {
        if (payloadById == null || payloadById.isEmpty()) {
            return;
        }
        ensureCollection(collection);
        List<Map<String, Object>> points = payloadById.entrySet().stream()
                .map(entry -> Map.of("id", entry.getKey(),
                        "payload", entry.getValue() == null ? Map.of() : entry.getValue()))
                .toList();
        client.post().uri("/collections/{collection}/points/payload?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("points", points))
                .retrieve().toBodilessEntity();
    }

    /**
     * 轮询等待 Qdrant 服务就绪，超时则抛出异常。
     *
     * @param timeout 最长等待时间
     * @throws IllegalStateException 超时仍未就绪时抛出，附带最后一次失败原因
     */
    public void waitUntilReady(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        RuntimeException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                client.get().uri("/collections").retrieve().toBodilessEntity();
                return;
            }
            catch (RuntimeException exception) {
                lastFailure = exception;
                sleepMillis(1_000);
            }
        }
        throw new IllegalStateException("Qdrant 未就绪: " + properties.qdrant().baseUrl(), lastFailure);
    }

    /**
     * 安全发布需求知识到 live alias：
     * 写入版本化物理 collection → 校验点数 → Qdrant Alias 原子切换/创建 → 清理旧版本。
     * 任一步失败时 Alias 保持不变，在线查询始终读取上一个完整版本。
     *
     * @param alias  检索侧 live alias（如 {@code requirements_live}），物理 collection 为 {@code <alias>-<ts>}
     * @param chunks 新版本的全部候选点
     */
    public void publishLiveAlias(String alias, List<ChunkRecord> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("cannot publish an empty requirement index");
        }
        String physical = alias + "-" + Instant.now().toEpochMilli();
        ensureCollection(physical);
        writePointBatches(physical, buildPointBatches(chunks, 32));
        verifyPhysicalCount(physical, chunks.size());
        publishAlias(alias, physical);
        retireOldCollections(alias, physical);
    }

    /** 回滚 live alias 到指定物理 collection（如上一个成功版本）；alias 不存在时创建指向目标。 */
    public void rollbackLiveAlias(String alias, String targetCollection) {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("alias must not be blank");
        }
        if (targetCollection == null || targetCollection.isBlank()) {
            throw new IllegalArgumentException("targetCollection must not be blank");
        }
        String current = aliasTarget(alias);
        if (targetCollection.equals(current)) {
            return;
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        if (current != null) {
            actions.add(Map.of("delete_alias", Map.of("alias_name", alias)));
        }
        actions.add(Map.of("create_alias", Map.of("collection_name", targetCollection, "alias_name", alias)));
        // 单请求内提交 delete+create：失败时旧 alias 不会被单独删除，回滚原子性可审计。
        postAliasActions(actions);
    }

    /** 查询 global alias 列表，返回 alias 当前指向的物理 collection；不存在时返回 null。 */
    public String aliasTarget(String alias) {
        Map<String, Object> response = client.get().uri("/aliases").retrieve()
                .body(new ParameterizedTypeReference<>() {});
        Map<String, Object> result = map(response == null ? null : response.get("result"));
        for (Object raw : list(result.get("aliases"))) {
            Map<String, Object> entry = map(raw);
            if (alias.equals(entry.get("alias_name"))) {
                return (String) entry.get("collection_name");
            }
        }
        return null;
    }

    /** 校验物理 collection 点数与预期一致；不一致时抛异常（Alias 不切换）。 */
    private void verifyPhysicalCount(String collection, int expected) {
        Map<String, Object> response = client.post().uri("/collections/{collection}/points/count", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        Map<String, Object> result = map(response == null ? null : response.get("result"));
        long count = ((Number) result.getOrDefault("count", 0)).longValue();
        if (count != expected) {
            throw new IllegalStateException("多源索引校验失败: collection " + collection
                    + " 期望 " + expected + " 个 point, 实际 " + count);
        }
    }

    /** 原子切换 Alias 到新物理 collection：alias 不存在则创建（并迁移删除旧物理 collection），存在则 swap（失败回退 delete+create）。 */
    private void publishAlias(String alias, String physical) {
        String current = aliasTarget(alias);
        Map<String, Object> action;
        if (current == null) {
            action = Map.of("create_alias", Map.of("collection_name", physical, "alias_name", alias));
            try {
                postAliasActions(action);
            } catch (HttpClientErrorException.Conflict conflict) {
                LOGGER.warn("alias {} 与遗留物理 collection 冲突，清理后重试: {}", alias, conflict.getMessage());
                deleteLegacyPhysical(alias);
                postAliasActions(action);
            }
        } else {
            action = Map.of("swap_aliases", List.of(
                    Map.of("collection", current, "alias", alias),
                    Map.of("collection", physical, "alias", alias)));
            try {
                postAliasActions(action);
            } catch (HttpClientErrorException.BadRequest exception) {
                // 本机 Qdrant 1.15.4 对 swap_aliases 解析失败（AliasOperations untagged enum 400）。
                // 回退为单请求 delete+create：两个动作放在同一批 actions 中原子执行，
                // 即使该请求失败，旧 alias 也未被单独删除，仍可在线查询。
                LOGGER.warn("swap_aliases 不可用（{}），回退单请求 delete+create 切换 alias {}", exception.getMessage(), alias);
                postAliasActions(List.of(
                        Map.of("delete_alias", Map.of("alias_name", alias)),
                        Map.of("create_alias", Map.of("collection_name", physical, "alias_name", alias))));
            }
        }
        if (current == null) {
            deleteLegacyPhysical(alias);
        }
    }

    private void postAliasActions(List<Map<String, Object>> actions) {
        client.post().uri("/collections/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("actions", actions))
                .retrieve().toBodilessEntity();
    }

    private void postAliasActions(Map<String, Object> action) {
        postAliasActions(List.of(action));
    }

    /** 首次发布迁移：删除与 alias 同名的旧物理 collection（旧数据，best-effort）。 */
    private void deleteLegacyPhysical(String alias) {
        try {
            client.delete().uri("/collections/{collection}", alias).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound exception) {
            // 无旧物理 collection（全新部署）
        }
    }

    /** 清理过期物理 collection：保留最新 2 个（当前 + 上一个成功版本），其余删除。 */
    private void retireOldCollections(String alias, String currentPhysical) {
        String prefix = alias + "-";
        Map<String, Object> response = client.get().uri("/collections").retrieve()
                .body(new ParameterizedTypeReference<>() {});
        Map<String, Object> result = map(response == null ? null : response.get("result"));
        List<String> physicals = new ArrayList<>();
        for (Object raw : list(result.get("collections"))) {
            Map<String, Object> entry = map(raw);
            String name = (String) entry.get("name");
            if (name != null && name.startsWith(prefix)) {
                physicals.add(name);
            }
        }
        physicals.sort(Comparator.naturalOrder());
        for (int index = 0; index < physicals.size() - 2; index++) {
            String stale = physicals.get(index);
            if (!stale.equals(currentPhysical)) {
                try {
                    client.delete().uri("/collections/{collection}", stale).retrieve().toBodilessEntity();
                    LOGGER.info("Retired stale requirement index collection {}", stale);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Failed to retire stale requirement index collection {}: {}", stale, exception.getMessage());
                }
            }
        }
    }

    /** 确保指定 collection 存在，不存在则按嵌入维度自动创建。 */
    private void ensureCollection(String collection) {
        if (initializedCollections.contains(collection)) {
            return;
        }
        synchronized (this) {
            if (initializedCollections.contains(collection)) {
                return;
            }
            RuntimeException lastFailure = null;
            for (int attempt = 1; attempt <= 10; attempt++) {
                try {
                    client.get().uri("/collections/{collection}", collection).retrieve().toBodilessEntity();
                    initializedCollections.add(collection);
                    return;
                }
                catch (HttpClientErrorException.NotFound exception) {
                    Map<String, Object> body = Map.of(
                            "vectors", Map.of("dense", Map.of("size", embeddingModel.dimensions(), "distance", "Cosine")),
                            "sparse_vectors", Map.of("sparse", Map.of("modifier", "idf")));
                    client.put().uri("/collections/{collection}", collection).contentType(MediaType.APPLICATION_JSON)
                            .body(body).retrieve().toBodilessEntity();
                    initializedCollections.add(collection);
                    return;
                }
                catch (ResourceAccessException exception) {
                    lastFailure = exception;
                    sleepMillis(1_000L * attempt);
                }
            }
            throw new IllegalStateException("无法连接 Qdrant collection: " + collection, lastFailure);
        }
    }

    /** 线程休眠指定毫秒，中断时恢复中断标志并抛异常。 */
    private void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Qdrant 时被中断", exception);
        }
    }

    /** 按 documentId 与 version 过滤删除已有分块。 */
    private void deleteVersion(String collection, String documentId, String version) {
        client.post().uri("/collections/{collection}/points/delete?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("filter", filter(documentId, version)))
                .retrieve().toBodilessEntity();
    }

    /** 构建 documentId + version 的 Qdrant 过滤条件。 */
    private Map<String, Object> filter(String documentId, String version) {
        return filter(documentId, version, Set.of());
    }

    /** 构建 documentId + version + 可选多源类型过滤的 Qdrant 过滤条件。 */
    private Map<String, Object> filter(String documentId, String version, Set<String> sourceTypes) {
        List<Map<String, Object>> must = new ArrayList<>(List.of(
                Map.of("key", "documentId", "match", Map.of("value", documentId)),
                Map.of("key", "version", "match", Map.of("value", version))));
        if (sourceTypes != null && !sourceTypes.isEmpty()) {
            Map<String, Object> match = sourceTypes.size() == 1
                    ? Map.of("value", sourceTypes.iterator().next())
                    : Map.of("any", List.copyOf(sourceTypes));
            must.add(Map.of("key", "sourceType", "match", match));
        }
        return Map.of("must", must);
    }

    /** 从查询响应中提取分块列表。 */
    private List<ChunkRecord> extractPoints(Map<String, Object> response) {
        Object result = response == null ? null : response.get("result");
        Map<String, Object> resultMap = map(result);
        Object points = resultMap.isEmpty() ? result : resultMap.get("points");
        return toRecords(list(points));
    }

    /** 批量将原始点对象转换为 ChunkRecord。 */
    private List<ChunkRecord> toRecords(List<Object> points) {
        return points.stream().map(this::toRecord).toList();
    }

    /** 将单个 Qdrant 点 payload 映射为 ChunkRecord。 */
    private ChunkRecord toRecord(Object raw) {
        Map<String, Object> point = map(raw);
        Map<String, Object> p = map(point.get("payload"));
        return new ChunkRecord(String.valueOf(point.get("id")), string(p, "documentId"), string(p, "version"),
                string(p, "filename"), string(p, "parentId"), string(p, "parentText"), string(p, "childText"),
                string(p, "contentHash"), integer(p, "parentOrder"), integer(p, "childOrder"),
                string(p, "sectionPath"), string(p, "heading"),
                string(p, "requirementId"), string(p, "module"),
                string(p, "acceptanceCriteria"),
                p.containsKey("sourceType") && p.get("sourceType") != null
                        ? String.valueOf(p.get("sourceType")) : "REQUIREMENT",
                string(p, "documentVersionId"), string(p, "authority"), string(p, "status"),
                string(p, "evidenceId"), string(p, "factKey"));
    }

    /** 获取配置的 collection 名称。 */
    private String collection() { return properties.qdrant().collection(); }
    /** 从 map 安全读取字符串字段。 */
    private String string(Map<String, Object> map, String key) { return String.valueOf(map.getOrDefault(key, "")); }
    /** null 安全字符串：null 返回空串。 */
    private String nullSafe(String value) { return value == null ? "" : value; }
    /** 从 map 安全读取整数字段。 */
    private int integer(Map<String, Object> map, String key) { return ((Number) map.getOrDefault(key, 0)).intValue(); }
    /** 安全转换为 Map，非 Map 时返回空 HashMap。 */
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> m ? (Map<String, Object>) m : new HashMap<>(); }
    /** 安全转换为 List，非 List 时返回空列表。 */
    @SuppressWarnings("unchecked") private List<Object> list(Object value) { return value instanceof List<?> l ? (List<Object>) l : List.of(); }
}
