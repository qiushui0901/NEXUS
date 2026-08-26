package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.KnowledgeClaimVectorPoint;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Claim 向量 Qdrant 发布器（§7）：复用 alias 原语但 payload 使用 {@link KnowledgeClaimVectorPoint}，
 * 不依赖 ChunkRecord 的字段假设。dense-only（无 sparse），一个点代表一个 Claim。
 * <p>
 * 发布流程：ensureCollection → writePointBatches → verifyPhysicalCount → switchAlias → retireOldCollections。
 * 任一步失败时 alias 保持不变，在线查询始终读取上一个完整版本。
 */
@Repository
public class KnowledgeClaimVectorQdrantStore {

    private final RestClient client;
    private final KnowledgeClaimVectorProperties properties;
    private final Set<String> initializedCollections = ConcurrentHashMap.newKeySet();
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(KnowledgeClaimVectorQdrantStore.class);

    public KnowledgeClaimVectorQdrantStore(RestClient qdrantRestClient,
                                           KnowledgeClaimVectorProperties properties) {
        this.client = qdrantRestClient;
        this.properties = properties;
    }

    /**
     * 写入物理 collection 并校验点数（不切换 alias）。
     * 任一步失败时 alias 不变，物理 collection 为半成品，可被后续 retireOldCollections 清理。
     */
    public void publishPhysicalCollection(String physicalCollection,
                                          List<KnowledgeClaimVectorPoint> points,
                                          List<float[]> vectors,
                                          int dimension) {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("cannot publish an empty claim vector index");
        }
        if (vectors == null || vectors.size() != points.size()) {
            throw new IllegalArgumentException("vectors size must match points size");
        }
        ensureCollection(physicalCollection, dimension);
        writePointBatches(physicalCollection, points, vectors, properties.batchSize());
        verifyPhysicalCount(physicalCollection, points.size());
    }

    /** 原子切换 alias 到新物理 collection，保留最近 {@code retainPhysicalCollections} 个。 */
    public void switchAlias(String alias, String physicalCollection) {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("alias must not be blank");
        }
        if (physicalCollection == null || physicalCollection.isBlank()) {
            throw new IllegalArgumentException("physicalCollection must not be blank");
        }
        String current = aliasTarget(alias);
        if (physicalCollection.equals(current)) {
            return;
        }
        Map<String, Object> action;
        if (current == null) {
            action = Map.of("create_alias", Map.of(
                    "collection_name", physicalCollection, "alias_name", alias));
            try {
                postAliasActions(action);
            } catch (HttpClientErrorException.Conflict conflict) {
                LOGGER.warn("alias {} 与遗留物理 collection 冲突，清理后重试: {}", alias, conflict.getMessage());
                deleteCollection(alias);
                postAliasActions(action);
            }
            deleteCollection(alias);
        } else {
            List<Map<String, Object>> actions = List.of(
                    Map.of("delete_alias", Map.of("alias_name", alias)),
                    Map.of("create_alias", Map.of(
                            "collection_name", physicalCollection, "alias_name", alias)));
            try {
                postAliasActions(actions);
            } catch (HttpClientErrorException.BadRequest exception) {
                LOGGER.warn("alias 切换失败 ({}), 重试单请求 delete+create: {}",
                        exception.getMessage(), alias);
                postAliasActions(actions);
            }
        }
        retireOldCollections(alias, physicalCollection);
    }

    /** 回滚 alias 到指定物理 collection（如上一个成功版本）。 */
    public void rollbackAlias(String alias, String targetCollection) {
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
        actions.add(Map.of("create_alias", Map.of(
                "collection_name", targetCollection, "alias_name", alias)));
        postAliasActions(actions);
    }

    /** 查询 alias 当前指向的物理 collection；不存在时返回 null。 */
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

    /** 向量检索命中：claimId + 分数 + 原始 Qdrant payload（治理字段从 SQLite 重新读取）。 */
    public record ClaimVectorHit(String claimId, double score, KnowledgeClaimVectorPoint point) {
    }

    /**
     * 通过 alias 向量检索最近的 Claim 点（高：Review 1——旧实现向 /points/search 发送裸 vector，
     * 与命名向量 dense 不兼容会被 Qdrant 拒绝；改为与 QdrantHybridStore 一致的 /points/query + using="dense"）。
     * 返回按 score 降序排列的命中列表，payload 含 Qdrant 投影时的快照字段。
     * Qdrant 不可用时返回空列表并写 warn——fail-safe 不影响其他来源检索。
     */
    public List<ClaimVectorHit> search(String alias, float[] queryVector, int limit) {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("alias must not be blank");
        }
        if (queryVector == null || queryVector.length == 0) {
            throw new IllegalArgumentException("queryVector must not be empty");
        }
        try {
            Map<String, Object> request = new java.util.LinkedHashMap<>();
            request.put("query", queryVector);
            request.put("using", "dense");
            request.put("limit", Math.max(1, limit));
            request.put("with_payload", true);
            request.put("with_vector", false);
            Map<String, Object> response = client.post()
                    .uri("/collections/{collection}/points/query", alias)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            List<ClaimVectorHit> hits = new ArrayList<>();
            for (Object raw : list(response == null ? null : response.get("result"))) {
                Map<String, Object> entry = map(raw);
                KnowledgeClaimVectorPoint point = toPoint(raw);
                double score = entry.get("score") instanceof Number n ? n.doubleValue() : 0.0;
                hits.add(new ClaimVectorHit(point.claimId(), score, point));
            }
            return hits;
        } catch (RuntimeException exception) {
            LOGGER.warn("向量检索失败 alias={} error={}", alias, exception.getMessage());
            return List.of();
        }
    }

    /** best-effort 点数查询；Qdrant 不可用时返回 -1。 */
    public long countPointsIfAvailable(String collection) {
        try {
            Map<String, Object> response = client.post()
                    .uri("/collections/{collection}/points/count", collection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            Map<String, Object> result = map(response == null ? null : response.get("result"));
            return ((Number) result.getOrDefault("count", 0)).longValue();
        } catch (RuntimeException exception) {
            LOGGER.debug("countPoints failed for {}: {}", collection, exception.getMessage());
            return -1;
        }
    }

    /** 滚动读取物理 collection 中的点（用于构建后样本校验）。 */
    public List<KnowledgeClaimVectorPoint> scrollPoints(String collection, int limit) {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("limit", Math.max(1, limit));
        request.put("with_payload", true);
        request.put("with_vector", false);
        Map<String, Object> response = client.post()
                .uri("/collections/{collection}/points/scroll", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        Map<String, Object> result = map(response == null ? null : response.get("result"));
        return toPoints(list(result.get("points")));
    }

    /**
     * 构建单个 Qdrant 点的 Map 表示（dense-only vector + Claim payload）。
     * package-private for testing.
     */
    Map<String, Object> buildPointMap(KnowledgeClaimVectorPoint point, float[] vector) {
        return Map.of(
                "id", KnowledgeClaimVectorModels.deterministicPointId(
                        point.projectId(), point.businessVersion(),
                        point.claimId(), point.projectionSchemaVersion()),
                "vector", Map.of("dense", vector),
                "payload", Map.ofEntries(
                        Map.entry("projectId", point.projectId()),
                        Map.entry("businessVersion", point.businessVersion()),
                        Map.entry("claimId", point.claimId()),
                        Map.entry("documentVersionId", nullSafe(point.documentVersionId())),
                        Map.entry("sourceType", point.sourceType().name()),
                        Map.entry("authority", point.authority().name()),
                        Map.entry("knowledgeStatus", nullSafe(point.knowledgeStatus())),
                        Map.entry("factKey", nullSafe(point.factKey())),
                        Map.entry("subject", nullSafe(point.subject())),
                        Map.entry("predicate", nullSafe(point.predicate())),
                        Map.entry("valueType", nullSafe(point.valueType())),
                        Map.entry("unit", nullSafe(point.unit())),
                        Map.entry("evidenceIds", point.evidenceIds()),
                        Map.entry("projectionGenerationId", nullSafe(point.projectionGenerationId())),
                        Map.entry("projectionSchemaVersion", nullSafe(point.projectionSchemaVersion())),
                        Map.entry("embeddingModel", nullSafe(point.embeddingModel())),
                        Map.entry("textHash", nullSafe(point.textHash()))));
    }

    /**
     * 物理 collection 不存在时创建（建 collection 幂等，高：Review 8——流式构建按页写入的第一步）。
     * 与采集同名集合已存在时直接返回。
     */
    public void createCollectionIfAbsent(String collection, int dimension) {
        ensureCollection(collection, dimension);
    }

    /**
     * 向已有物理 collection 追加一批点（分块 upsert）。
     * 高：Review 8——流式构建逐块写，避免 20 万条 point/vector 全部驻留内存。
     */
    public void appendPoints(String collection, List<KnowledgeClaimVectorPoint> points,
                             List<float[]> vectors) {
        if (points == null || points.isEmpty()) {
            return;
        }
        if (vectors == null || vectors.size() != points.size()) {
            throw new IllegalArgumentException("vectors size must match points size");
        }
        writePointBatches(collection, points, vectors, properties.batchSize());
    }

    /**
     * 校验物理 collection 实际点数（流式构建收尾）。点数不一致时抛异常。
     */
    public void verifyPointCount(String collection, int expected) {
        verifyPhysicalCount(collection, expected);
    }

    // ── private ───────────────────────────────────────────────────────────

    private void ensureCollection(String collection, int dimension) {
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
                    client.get().uri("/collections/{collection}", collection)
                            .retrieve().toBodilessEntity();
                    initializedCollections.add(collection);
                    return;
                } catch (HttpClientErrorException.NotFound exception) {
                    Map<String, Object> body = Map.of(
                            "vectors", Map.of("dense",
                                    Map.of("size", dimension, "distance", "Cosine")));
                    client.put().uri("/collections/{collection}", collection)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body).retrieve().toBodilessEntity();
                    initializedCollections.add(collection);
                    return;
                } catch (org.springframework.web.client.ResourceAccessException exception) {
                    lastFailure = exception;
                    sleepMillis(1_000L * attempt);
                }
            }
            throw new IllegalStateException("无法连接 Qdrant collection: " + collection, lastFailure);
        }
    }

    private void writePointBatches(String collection, List<KnowledgeClaimVectorPoint> points,
                                   List<float[]> vectors, int batchSize) {
        for (int start = 0; start < points.size(); start += batchSize) {
            int end = Math.min(start + batchSize, points.size());
            List<Map<String, Object>> batch = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                batch.add(buildPointMap(points.get(i), vectors.get(i)));
            }
            client.put().uri("/collections/{collection}/points?wait=true", collection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("points", batch))
                    .retrieve().toBodilessEntity();
        }
    }

    private void verifyPhysicalCount(String collection, int expected) {
        Map<String, Object> response = client.post()
                .uri("/collections/{collection}/points/count", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        Map<String, Object> result = map(response == null ? null : response.get("result"));
        long count = ((Number) result.getOrDefault("count", 0)).longValue();
        if (count != expected) {
            throw new IllegalStateException("Claim 向量索引校验失败: collection " + collection
                    + " 期望 " + expected + " 个 point, 实际 " + count);
        }
    }

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
        int retain = properties.retainPhysicalCollections();
        for (int index = 0; index < physicals.size() - retain; index++) {
            String stale = physicals.get(index);
            if (!stale.equals(currentPhysical)) {
                try {
                    deleteCollection(stale);
                    LOGGER.info("Retired stale claim vector collection {}", stale);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Failed to retire stale claim vector collection {}: {}",
                            stale, exception.getMessage());
                }
            }
        }
    }

    private void deleteCollection(String collection) {
        try {
            client.delete().uri("/collections/{collection}", collection)
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound exception) {
            // no such collection (fresh deploy)
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

    @SuppressWarnings("unchecked")
    private List<KnowledgeClaimVectorPoint> toPoints(List<Object> points) {
        return points.stream().map(this::toPoint).toList();
    }

    @SuppressWarnings("unchecked")
    private KnowledgeClaimVectorPoint toPoint(Object raw) {
        Map<String, Object> point = map(raw);
        Map<String, Object> p = map(point.get("payload"));
        return new KnowledgeClaimVectorPoint(
                string(p, "projectId"),
                string(p, "businessVersion"),
                string(p, "claimId"),
                string(p, "documentVersionId"),
                com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType
                        .normalize(string(p, "sourceType")),
                com.example.requirementrag.conflict.KnowledgeConflictModels.Authority
                        .valueOf(string(p, "authority")),
                string(p, "knowledgeStatus"),
                string(p, "factKey"),
                string(p, "subject"),
                string(p, "predicate"),
                string(p, "valueType"),
                string(p, "unit"),
                list(p.get("evidenceIds")).stream()
                        .map(String::valueOf).toList(),
                string(p, "projectionGenerationId"),
                string(p, "projectionSchemaVersion"),
                string(p, "embeddingModel"),
                string(p, "textHash"));
    }

    private String string(Map<String, Object> map, String key) {
        return String.valueOf(map.getOrDefault(key, ""));
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Qdrant 时被中断", exception);
        }
    }
}
