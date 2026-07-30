package com.example.requirementrag.code;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.retrieval.EmbeddingBatcher;
import com.example.requirementrag.retrieval.SparseVectorizer;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 代码向量 Qdrant 存储，复用文档 RAG 的 dense+sparse hybrid search 方案。
 * 所有公开方法接受 collection 参数，支持多项目按不同 collection 隔离。
 */
@Component
public class CodeQdrantStore {

    private static final int CANDIDATE_MULTIPLIER = 3;
    private static final int MIN_CANDIDATE_LIMIT = 50;
    private static final int DENSE_SOURCE_PREFIX_CHARACTERS = 800;

    private final RestClient client;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingBatcher embeddingBatcher;
    private final SparseVectorizer sparseVectorizer;
    private final RagProperties properties;
    private final Set<String> initializedCollections = ConcurrentHashMap.newKeySet();

    /** 注入 Qdrant 客户端、嵌入模型、稀疏向量化器与配置。 */
    public CodeQdrantStore(RestClient qdrantRestClient, EmbeddingModel embeddingModel,
                           EmbeddingBatcher embeddingBatcher, SparseVectorizer sparseVectorizer,
                           RagProperties properties) {
        this.client = qdrantRestClient;
        this.embeddingModel = embeddingModel;
        this.embeddingBatcher = embeddingBatcher;
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
        List<List<Map<String, Object>>> pointBatches = buildPointBatches(chunks, 32);
        deleteProject(collection, projectId);
        writePointBatches(collection, pointBatches);
    }

    /** 增量写入代码 chunk，不删除项目内其他文件。 */
    public void upsertChunks(String collection, List<CodeChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        ensureCollection(collection);
        writePointBatches(collection, buildPointBatches(chunks, 32));
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
        int candidateLimit = Math.max(limit * CANDIDATE_MULTIPLIER, MIN_CANDIDATE_LIMIT);
        int prefetchLimit = candidateLimit * CANDIDATE_MULTIPLIER;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prefetch", List.of(
                Map.of("query", dense, "using", "dense", "limit", prefetchLimit, "filter", filter(projectId)),
                Map.of("query", Map.of("indices", sparse.indices(), "values", sparse.values()),
                        "using", "sparse", "limit", prefetchLimit, "filter", filter(projectId))));
        body.put("query", Map.of("fusion", "rrf"));
        body.put("limit", candidateLimit);
        body.put("with_payload", true);
        Map<String, Object> response = executeIdempotentQuery(() -> client.post()
                .uri("/collections/{collection}/points/query", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {}));
        return rerankCandidates(query, extractPoints(response), limit);
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


    private List<List<Map<String, Object>>> buildPointBatches(List<CodeChunk> chunks, int batchSize) {
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

    private List<Map<String, Object>> buildPoints(List<CodeChunk> chunks) {
        List<String> retrievalTexts = chunks.stream().map(CodeQdrantStore::retrievalText).toList();
        List<String> denseRetrievalTexts = chunks.stream().map(CodeQdrantStore::denseRetrievalText).toList();
        List<float[]> denseVectors = embeddingBatcher.embedAll(denseRetrievalTexts);
        List<Map<String, Object>> points = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            CodeChunk chunk = chunks.get(index);
            SparseVectorizer.SparseVector sparse = sparseVectorizer.vectorize(retrievalTexts.get(index));
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
                            "language", chunk.language(),
                            "startLine", chunk.startLine(),
                            "endLine", chunk.endLine(),
                            "text", chunk.text(),
                            "contentHash", chunk.contentHash())));
        }
        return points;
    }

    /**
     * 构造仅用于向量检索的代码文本。payload 仍保存原始源码，避免元数据污染引用摘录。
     * 符号与路径元数据放在正文前，防止长方法体稀释实现入口、类名和方法名信号。
     */
    static String retrievalText(CodeChunk chunk) {
        return retrievalMetadata(chunk) + "source code:\n" + safeText(chunk.text());
    }

    /**
     * Dense embedding 只保留结构化元数据和源码前缀，避免超长方法体稀释符号与职责语义。
     * Sparse 向量仍使用完整 retrievalText，以保留精确标识符和源码词项召回。
     */
    static String denseRetrievalText(CodeChunk chunk) {
        String source = safeText(chunk.text());
        String prefix = source.length() <= DENSE_SOURCE_PREFIX_CHARACTERS
                ? source : source.substring(0, DENSE_SOURCE_PREFIX_CHARACTERS);
        return retrievalMetadata(chunk) + "source code prefix:\n" + prefix;
    }

    private static String retrievalMetadata(CodeChunk chunk) {
        String filePath = safeText(chunk.filePath());
        String symbolType = safeText(chunk.symbolType());
        String symbolName = safeText(chunk.symbolName());
        return "file path: " + filePath + '\n'
                + "symbol type: " + symbolType + '\n'
                + "symbol name: " + symbolName + '\n'
                + "symbol terms: " + splitIdentifier(symbolName) + '\n'
                + "code role: " + codeRole(filePath, symbolType, symbolName) + '\n';
    }

    /**
     * 在有限 RRF 候选池内做确定性结构重排。原始名次仍是主信号，仅对明确的符号词和代码角色意图加分。
     */
    static List<CodeChunk> rerankCandidates(String query, List<CodeChunk> candidates, int limit) {
        if (candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<RankedCodeChunk> ranked = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            CodeChunk chunk = candidates.get(index);
            ranked.add(new RankedCodeChunk(chunk, index, candidateScore(query, chunk, index, candidates.size())));
        }
        return ranked.stream()
                .sorted(Comparator.comparingDouble(RankedCodeChunk::score).reversed()
                        .thenComparingInt(RankedCodeChunk::originalRank))
                .limit(Math.min(limit, ranked.size()))
                .map(RankedCodeChunk::chunk)
                .toList();
    }

    private static double candidateScore(String query, CodeChunk chunk, int index, int candidateCount) {
        String normalizedQuery = safeText(query).toLowerCase(java.util.Locale.ROOT);
        String normalizedSymbol = safeText(chunk.symbolName()).toLowerCase(java.util.Locale.ROOT);
        String symbolTerms = splitIdentifier(chunk.symbolName()).toLowerCase(java.util.Locale.ROOT);
        String path = safeText(chunk.filePath()).toLowerCase(java.util.Locale.ROOT);
        double score = 1.0 - (0.30 * index / Math.max(candidateCount - 1.0, 1.0));

        if (!normalizedSymbol.isBlank() && compact(normalizedQuery).contains(compact(normalizedSymbol))) {
            score += 0.50;
        }
        int matchedTerms = 0;
        for (String term : symbolTerms.split("\\s+")) {
            if (term.length() >= 3 && normalizedQuery.contains(term)) {
                matchedTerms++;
            }
        }
        score += Math.min(matchedTerms * 0.12, 0.36);

        boolean serviceImplementation = path.contains("/service/impl/") || normalizedSymbol.endsWith("impl");
        boolean controller = path.contains("/controller/");
        boolean test = path.contains("/src/test/") || path.contains("/test/");
        if (asksForServiceImplementation(normalizedQuery)) {
            if (serviceImplementation) {
                score += 0.42;
            }
            else if (controller) {
                score -= 0.08;
            }
        }
        else if (asksForBusinessBehavior(normalizedQuery) && serviceImplementation) {
            score += 0.24;
        }
        if (asksForController(normalizedQuery) && controller) {
            score += 0.35;
        }
        if (asksForTest(normalizedQuery)) {
            score += test ? 0.35 : 0.0;
        }
        else if (test) {
            score -= 0.12;
        }
        if ("method".equalsIgnoreCase(chunk.symbolType()) && asksForBusinessBehavior(normalizedQuery)) {
            score += 0.04;
        }
        return score;
    }

    private static boolean asksForServiceImplementation(String query) {
        return containsAny(query, "服务实现", "实现入口", "业务逻辑", "service implementation",
                "implementation entry", "business logic");
    }

    private static boolean asksForBusinessBehavior(String query) {
        return asksForServiceImplementation(query) || containsAny(query, "如何", "保证", "触发", "同步",
                "生成", "处理", "上传", "签发", "限制", "缓存", "排序", "去重", "返回",
                "how ", "ensure", "trigger", "synchronize", "generate", "handle", "upload", "return");
    }

    private static boolean asksForController(String query) {
        return containsAny(query, "控制器", "请求入口", "api endpoint", "controller");
    }

    private static boolean asksForTest(String query) {
        return containsAny(query, "测试", "验证用例", "test case", "unit test", "integration test");
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static String compact(String value) {
        return value.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private record RankedCodeChunk(CodeChunk chunk, int originalRank, double score) {
    }

    private static String codeRole(String filePath, String symbolType, String symbolName) {
        String normalizedPath = filePath.toLowerCase(java.util.Locale.ROOT);
        String normalizedName = symbolName.toLowerCase(java.util.Locale.ROOT);
        if (normalizedPath.contains("/src/test/") || normalizedPath.contains("/test/")) {
            return "test verification 测试 验证";
        }
        if (normalizedPath.contains("/service/impl/") || normalizedName.endsWith("impl")) {
            return "service implementation business logic 服务实现 业务逻辑 实现入口";
        }
        if (normalizedPath.contains("/controller/")) {
            return "controller api endpoint request entry 控制器 接口 请求入口";
        }
        if (normalizedPath.contains("/service/") || "interface".equalsIgnoreCase(symbolType)) {
            return "service contract interface 服务接口 契约";
        }
        if (normalizedPath.contains("/consumer/") || normalizedPath.contains("/listener/")) {
            return "message consumer listener event handler 消息消费 事件处理";
        }
        if (normalizedPath.contains("/repository/") || normalizedPath.contains("/mapper/")) {
            return "data access repository mapper 数据访问 持久化";
        }
        return "code symbol implementation 代码符号 实现";
    }

    private static String splitIdentifier(String value) {
        return safeText(value)
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("[_$]+", " ")
                .trim();
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    /**
     * Qdrant query uses POST but is read-only and safe to repeat once after a transient client-side I/O failure.
     * HTTP status and serialization-contract failures are deliberately not retried.
     */
    static <T> T executeIdempotentQuery(Supplier<T> query) {
        try {
            return query.get();
        }
        catch (RuntimeException exception) {
            if (!isTransientQueryIoFailure(exception)) {
                throw exception;
            }
            return query.get();
        }
    }

    private static boolean isTransientQueryIoFailure(RuntimeException exception) {
        if (exception instanceof ResourceAccessException) {
            return true;
        }
        if (!(exception instanceof HttpMessageNotWritableException)) {
            return false;
        }
        Throwable cause = exception.getCause();
        while (cause != null) {
            if (cause instanceof java.io.IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
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
                integer(p, "startLine"), integer(p, "endLine"), string(p, "text"), string(p, "contentHash"),
                language(p));
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

    private String language(Map<String, Object> payload) {
        String stored = string(payload, "language");
        return stored.isBlank() ? CodeLanguage.fromPath(string(payload, "filePath")).id() : stored;
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
