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

    /** 替换指定文档版本的全部分块：先删后批量写入。使用默认 collection。 */
    public void replaceVersion(String documentId, String version, List<ChunkRecord> chunks) {
        replaceVersion(collection(), documentId, version, chunks);
    }

    /** 替换指定文档版本的全部分块：先删后批量写入。 */
    public void replaceVersion(String collection, String documentId, String version, List<ChunkRecord> chunks) {
        ensureCollection(collection);
        List<List<Map<String, Object>>> pointBatches = buildPointBatches(chunks, 64);
        deleteVersion(collection, documentId, version);
        writePointBatches(collection, pointBatches);
    }


    private List<List<Map<String, Object>>> buildPointBatches(List<ChunkRecord> chunks, int batchSize) {
        List<List<Map<String, Object>>> batches = new ArrayList<>();
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, chunks.size());
            batches.add(buildPoints(chunks.subList(start, end)));
        }
        return batches;
    }

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

    /** 执行稠密+稀疏 prefetch 与 RRF 融合的混合检索。使用默认 collection。 */
    public List<ChunkRecord> hybridSearch(String query, String documentId, String version) {
        return hybridSearch(collection(), query, documentId, version);
    }

    /** 执行稠密+稀疏 prefetch 与 RRF 融合的混合检索。 */
    public List<ChunkRecord> hybridSearch(String collection, String query, String documentId, String version) {
        ensureCollection(collection);
        float[] dense = embeddingModel.embed(query);
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

    /** 执行混合检索并返回带 Qdrant 原生分数的结果。 */
    public List<ScoredChunk> hybridSearchWithScores(String collection, String query, String documentId, String version) {
        ensureCollection(collection);
        float[] dense = embeddingModel.embed(query);
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

    private ScoredChunk toScoredRecord(Object raw) {
        Map<String, Object> point = map(raw);
        double score = point.containsKey("score") ? ((Number) point.get("score")).doubleValue() : 0.0;
        return new ScoredChunk(toRecord(raw), score);
    }

    /** 统计指定文档版本的分块数量。使用默认 collection。 */
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

    /** 分页滚动获取指定文档版本的全部 payload。使用默认 collection。 */
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

    /** 轮询等待 Qdrant 服务就绪，超时则抛出异常。 */
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
