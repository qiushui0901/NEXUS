package com.example.requirementrag.code;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.retrieval.SparseVectorizer;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 代码向量 Qdrant 存储，复用文档 RAG 的 dense+sparse hybrid search 方案。
 * 所有公开方法接受 collection 参数，支持多项目按不同 collection 隔离。
 */
@Component
public class CodeQdrantStore {

    private final RestClient client;
    private final EmbeddingModel embeddingModel;
    private final SparseVectorizer sparseVectorizer;
    private final RagProperties properties;
    private final Set<String> initializedCollections = ConcurrentHashMap.newKeySet();

    /** 注入 Qdrant 客户端、嵌入模型、稀疏向量化器与配置。 */
    public CodeQdrantStore(RestClient qdrantRestClient, EmbeddingModel embeddingModel,
                           SparseVectorizer sparseVectorizer, RagProperties properties) {
        this.client = qdrantRestClient;
        this.embeddingModel = embeddingModel;
        this.sparseVectorizer = sparseVectorizer;
        this.properties = properties;
    }

    /** 替换某个项目的全部代码 chunk。使用默认 collection。 */
    public void replaceProject(String projectId, List<CodeChunk> chunks) {
        replaceProject(collection(), projectId, chunks);
    }

    /** 替换某个项目的全部代码 chunk。 */
    public void replaceProject(String collection, String projectId, List<CodeChunk> chunks) {
        ensureCollection(collection);
        deleteProject(collection, projectId);
        upsertChunks(collection, chunks);
    }

    /** 增量写入代码 chunk，不删除项目内其他文件。 */
    public void upsertChunks(String collection, List<CodeChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        ensureCollection(collection);
        for (int start = 0; start < chunks.size(); start += 32) {
            int end = Math.min(start + 32, chunks.size());
            List<CodeChunk> batch = chunks.subList(start, end);
            client.put().uri("/collections/{collection}/points?wait=true", collection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("points", buildPoints(batch)))
                    .retrieve().toBodilessEntity();
        }
    }

    /** 删除某个项目下指定文件的全部代码 chunk。 */
    public void deleteFileChunks(String collection, String projectId, String filePath) {
        ensureCollection(collection);
        client.post().uri("/collections/{collection}/points/delete?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("filter", fileFilter(projectId, filePath)))
                .retrieve().toBodilessEntity();
    }

    /** 对代码 chunk 做 dense+sparse 混合检索。使用默认 collection。 */
    public List<CodeChunk> hybridSearch(String query, String projectId, int limit) {
        return hybridSearch(collection(), query, projectId, limit);
    }

    /** 对代码 chunk 做 dense+sparse 混合检索。 */
    public List<CodeChunk> hybridSearch(String collection, String query, String projectId, int limit) {
        ensureCollection(collection);
        float[] dense = embeddingModel.embed(query);
        SparseVectorizer.SparseVector sparse = sparseVectorizer.vectorize(query);
        int prefetchLimit = Math.max(limit * 3, 20);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prefetch", List.of(
                Map.of("query", dense, "using", "dense", "limit", prefetchLimit, "filter", filter(projectId)),
                Map.of("query", Map.of("indices", sparse.indices(), "values", sparse.values()),
                        "using", "sparse", "limit", prefetchLimit, "filter", filter(projectId))));
        body.put("query", Map.of("fusion", "rrf"));
        body.put("limit", limit);
        body.put("with_payload", true);
        Map<String, Object> response = client.post().uri("/collections/{collection}/points/query", collection)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return extractPoints(response);
    }

    /** 统计项目代码 chunk 数。使用默认 collection。 */
    public long countProject(String projectId) {
        return countProject(collection(), projectId);
    }

    /** 统计项目代码 chunk 数。 */
    public long countProject(String collection, String projectId) {
        ensureCollection(collection);
        Map<String, Object> response = client.post().uri("/collections/{collection}/points/count", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("filter", filter(projectId)))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        Map<String, Object> result = map(response == null ? null : response.get("result"));
        return ((Number) result.getOrDefault("count", 0)).longValue();
    }

    private List<Map<String, Object>> buildPoints(List<CodeChunk> chunks) {
        List<String> texts = chunks.stream().map(CodeChunk::text).toList();
        List<float[]> denseVectors = texts.parallelStream().map(embeddingModel::embed).toList();
        List<Map<String, Object>> points = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            CodeChunk chunk = chunks.get(index);
            SparseVectorizer.SparseVector sparse = sparseVectorizer.vectorize(chunk.text());
            points.add(Map.of(
                    "id", chunk.id(),
                    "vector", Map.of("dense", denseVectors.get(index),
                            "sparse", Map.of("indices", sparse.indices(), "values", sparse.values())),
                    "payload", Map.of(
                            "projectId", chunk.projectId(),
                            "commitSha", chunk.commitSha(),
                            "filePath", chunk.filePath(),
                            "symbolType", chunk.symbolType(),
                            "symbolName", chunk.symbolName(),
                            "startLine", chunk.startLine(),
                            "endLine", chunk.endLine(),
                            "text", chunk.text(),
                            "contentHash", chunk.contentHash())));
        }
        return points;
    }

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
            throw new IllegalStateException("无法连接 Qdrant code collection: " + collection, lastFailure);
        }
    }

    private void deleteProject(String collection, String projectId) {
        client.post().uri("/collections/{collection}/points/delete?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("filter", filter(projectId)))
                .retrieve().toBodilessEntity();
    }

    private Map<String, Object> filter(String projectId) {
        return Map.of("must", List.of(Map.of("key", "projectId", "match", Map.of("value", projectId))));
    }

    private Map<String, Object> fileFilter(String projectId, String filePath) {
        return Map.of("must", List.of(
                Map.of("key", "projectId", "match", Map.of("value", projectId)),
                Map.of("key", "filePath", "match", Map.of("value", filePath))));
    }

    private List<CodeChunk> extractPoints(Map<String, Object> response) {
        Object result = response == null ? null : response.get("result");
        Map<String, Object> resultMap = map(result);
        Object points = resultMap.isEmpty() ? result : resultMap.get("points");
        return list(points).stream().map(this::toChunk).toList();
    }

    private CodeChunk toChunk(Object raw) {
        Map<String, Object> point = map(raw);
        Map<String, Object> p = map(point.get("payload"));
        return new CodeChunk(String.valueOf(point.get("id")), string(p, "projectId"), string(p, "commitSha"),
                string(p, "filePath"), string(p, "symbolType"), string(p, "symbolName"),
                integer(p, "startLine"), integer(p, "endLine"), string(p, "text"), string(p, "contentHash"));
    }

    private void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Qdrant 时被中断", exception);
        }
    }

    private String collection() {
        return properties.code().collection();
    }

    private String string(Map<String, Object> map, String key) {
        return String.valueOf(map.getOrDefault(key, ""));
    }

    private int integer(Map<String, Object> map, String key) {
        return ((Number) map.getOrDefault(key, 0)).intValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }
}
