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
import java.util.ArrayList;
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
        replaceVersion(collection(), documentId, version, chunks);
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
        ensureCollection(collection);
        if (chunks == null || chunks.isEmpty()) {
            deleteVersion(collection, documentId, version);
            return;
        }
        List<List<Map<String, Object>>> pointBatches = buildPointBatches(chunks, 64);
        java.util.Set<String> oldIds = collectPointIds(collection, documentId, version);
        writePointBatches(collection, pointBatches);
        java.util.Set<String> newIds = new java.util.LinkedHashSet<>();
        for (ChunkRecord chunk : chunks) {
            newIds.add(chunk.id());
        }
        verifyVersion(collection, documentId, version, newIds);
        java.util.Set<String> staleIds = new java.util.HashSet<>(oldIds);
        staleIds.removeAll(newIds);
        if (!staleIds.isEmpty()) {
            deletePoints(collection, staleIds);
        }
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
        List<List<Map<String, Object>>> batches = new ArrayList<>();
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, chunks.size());
            batches.add(buildPoints(chunks.subList(start, end)));
        }
        return batches;
    }

    /** 逐批 PUT 写入 Qdrant 点，wait=true 等待持久化完成。 */
    private void writePointBatches(String collection, List<List<Map<String, Object>>> batches) {
        for (List<Map<String, Object>> points : batches) {
            client.put().uri("/collections/{collection}/points?wait=true", collection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("points", points))
                    .retrieve().toBodilessEntity();
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
                    "payload", Map.of(
                            "documentId", chunk.documentId(), "version", chunk.version(), "filename", chunk.filename(),
                            "parentId", chunk.parentId(), "parentText", chunk.parentText(), "childText", chunk.childText(),
                            "contentHash", chunk.contentHash(), "parentOrder", chunk.parentOrder(), "childOrder", chunk.childOrder())));
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
        Map<String, Object> response = client.post().uri("/collections/{collection}/points/count", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("filter", filter(documentId, version)))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        Map<String, Object> result = map(response == null ? null : response.get("result"));
        return ((Number) result.getOrDefault("count", 0)).longValue();
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
        return Map.of("must", List.of(
                Map.of("key", "documentId", "match", Map.of("value", documentId)),
                Map.of("key", "version", "match", Map.of("value", version))));
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
                string(p, "contentHash"), integer(p, "parentOrder"), integer(p, "childOrder"));
    }

    /** 获取配置的 collection 名称。 */
    private String collection() { return properties.qdrant().collection(); }
    /** 从 map 安全读取字符串字段。 */
    private String string(Map<String, Object> map, String key) { return String.valueOf(map.getOrDefault(key, "")); }
    /** 从 map 安全读取整数字段。 */
    private int integer(Map<String, Object> map, String key) { return ((Number) map.getOrDefault(key, 0)).intValue(); }
    /** 安全转换为 Map，非 Map 时返回空 HashMap。 */
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> m ? (Map<String, Object>) m : new HashMap<>(); }
    /** 安全转换为 List，非 List 时返回空列表。 */
    @SuppressWarnings("unchecked") private List<Object> list(Object value) { return value instanceof List<?> l ? (List<Object>) l : List.of(); }
}
