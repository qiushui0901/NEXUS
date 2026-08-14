package com.example.requirementrag.code;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.rerank.BgeReranker;
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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
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
    private static final JsonMapper JSON = new JsonMapper();
    private static final String ALIAS_RESOURCE = "/code-query-aliases.json";

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(CodeQdrantStore.class);

    private final RestClient client;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingBatcher embeddingBatcher;
    private final SparseVectorizer sparseVectorizer;
    private final RagProperties properties;
    private final BgeReranker bgeReranker;
    private final CodeQueryAnalyzer queryAnalyzer;
    private final Set<String> initializedCollections = ConcurrentHashMap.newKeySet();
    private final Set<String> descDenseChecked = ConcurrentHashMap.newKeySet();
    private final Set<String> descDenseCollections = ConcurrentHashMap.newKeySet();

    /** 注入 Qdrant 客户端、嵌入模型、稀疏向量化器与配置。 */
    public CodeQdrantStore(RestClient qdrantRestClient, EmbeddingModel embeddingModel,
                           EmbeddingBatcher embeddingBatcher, SparseVectorizer sparseVectorizer,
                           RagProperties properties) {
        this(qdrantRestClient, embeddingModel, embeddingBatcher, sparseVectorizer, properties, null);
    }

    /** 注入可选 BGE 重排器；为 null 时跳过代码语义重排。 */
    @org.springframework.beans.factory.annotation.Autowired
    public CodeQdrantStore(RestClient qdrantRestClient, EmbeddingModel embeddingModel,
                           EmbeddingBatcher embeddingBatcher, SparseVectorizer sparseVectorizer,
                           RagProperties properties, BgeReranker bgeReranker) {
        this(qdrantRestClient, embeddingModel, embeddingBatcher, sparseVectorizer, properties, bgeReranker,
                new CodeQueryAnalyzer());
    }

    /** 注入可选 BGE 重排器与查询解析器；查询解析器为 null 时结构化重排信号不生效。 */
    public CodeQdrantStore(RestClient qdrantRestClient, EmbeddingModel embeddingModel,
                           EmbeddingBatcher embeddingBatcher, SparseVectorizer sparseVectorizer,
                           RagProperties properties, BgeReranker bgeReranker,
                           CodeQueryAnalyzer queryAnalyzer) {
        this.client = qdrantRestClient;
        this.embeddingModel = embeddingModel;
        this.embeddingBatcher = embeddingBatcher;
        this.sparseVectorizer = sparseVectorizer;
        this.properties = properties;
        this.bgeReranker = bgeReranker;
        this.queryAnalyzer = queryAnalyzer;
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

    /**
     * 安全发布一个项目的全量代码索引：
     * 写入版本化物理 collection → 校验点数 → Qdrant Alias 原子切换 → 清理旧版本。
     * 任一步失败时 Alias 保持不变，在线查询始终读取上一个完整版本。
     *
     * @param alias    检索侧使用的别名（如 {@code code_x_active}），物理 collection 为 {@code <alias>-<ts>}
     * @param projectId 项目 ID
     * @param chunks    新版本的全部代码 chunk
     */
    public void publishProject(String alias, String projectId, List<CodeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("cannot publish an empty code index");
        }
        String physical = alias + "-" + Instant.now().toEpochMilli();
        ensureCollection(physical);
        writePointBatches(physical, buildPointBatches(chunks, 32));
        verifyCollectionPoints(physical, projectId, chunks.size());
        publishAlias(alias, physical);
        retireOldCollections(alias, physical);
    }

    /** 校验物理 collection 中指定项目的点数与预期一致；不一致时抛异常（Alias 不切换）。 */
    private void verifyCollectionPoints(String collection, String projectId, int expected) {
        Map<String, Object> response = client.post().uri("/collections/{collection}/points/count", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("filter", filter(projectId)))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        Map<String, Object> result = map(response == null ? null : response.get("result"));
        long count = ((Number) result.getOrDefault("count", 0)).longValue();
        if (count != expected) {
            throw new IllegalStateException("代码索引校验失败: collection " + collection
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
                // 同名遗留物理 collection 与 alias 命名冲突（历史遗留的空壳），删除后重试。
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
                // 回退 delete+create：非原子但有兜底，首次遇到时记录一次。
                LOGGER.warn("swap_aliases 不可用（{}），回退 delete+create 切换 alias {}", exception.getMessage(), alias);
                postAliasActions(Map.of("delete_alias", Map.of("alias_name", alias)));
                postAliasActions(Map.of("create_alias", Map.of("collection_name", physical, "alias_name", alias)));
            }
        }
        if (current == null) {
            deleteLegacyPhysical(alias);
        }
    }

    private void postAliasActions(Map<String, Object> action) {
        client.post().uri("/collections/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("actions", List.of(action)))
                .retrieve().toBodilessEntity();
    }

    /** 查询全局 Alias 列表，返回 alias 当前指向的物理 collection；alias 不存在时返回 null。 */
    private String aliasTarget(String alias) {
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
                    LOGGER.info("Retired stale code index collection {}", stale);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Failed to retire stale code index collection {}: {}", stale, exception.getMessage());
                }
            }
        }
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

    /** 滚动读取某个项目下指定文件在 collection 中的全部现有 chunk ID（增量索引替换前的旧 ID 快照，跨分页）。 */
    public List<String> scrollChunkIds(String collection, String projectId, String filePath, int limit) {
        ensureCollection(collection);
        List<String> ids = new java.util.ArrayList<>();
        Object offset = null;
        int pageSize = Math.min(Math.max(limit, 1), 100_000);
        while (true) {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("filter", fileFilter(projectId, filePath));
            body.put("with_payload", false);
            body.put("limit", pageSize);
            if (offset != null) body.put("offset", offset);
            Map<String, Object> response = client.post()
                    .uri("/collections/{collection}/points/scroll", collection)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            Map<String, Object> result = response == null ? Map.of() : map(response.get("result"));
            List<Object> points = list(result.get("points"));
            for (Object point : points) {
                String id = String.valueOf(map(point).get("id"));
                if (!id.isBlank() && !"null".equals(id)) ids.add(id);
            }
            offset = result.get("next_page_offset");
            if (offset == null || points.isEmpty() || ids.size() >= limit) break;
        }
        return List.copyOf(ids);
    }

    /** 按 point ID 删除指定代码 chunk（增量替换时只删旧 ID，不影响新写入的 chunk）。 */
    public void deleteChunks(String collection, List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        ensureCollection(collection);
        client.post().uri("/collections/{collection}/points/delete?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("points", ids))
                .retrieve().toBodilessEntity();
    }

    /** 对代码 chunk 做 dense+sparse 混合检索。使用默认 collection。 */
    public List<CodeChunk> hybridSearch(String query, String projectId, int limit) {
        return hybridSearch(collection(), query, projectId, limit);
    }

    /** 对代码 chunk 做 dense+sparse 混合检索。 */
    public List<CodeChunk> hybridSearch(String collection, String query, String projectId, int limit) {
        return fusedSearch(collection, query, projectId, limit);
    }

    /**
     * 返回 RRF 候选与确定性精排结果，供离线评测定位候选召回和最终排序损失。
     * 两组结果均有界，且执行路径与 {@link #hybridSearch(String, String, String, int)} 完全一致。
     */
    public CodeSearchTrace hybridSearchTrace(String collection, String query, String projectId, int limit) {
        ensureCollection(collection);
        float[] dense = embeddingBatcher.embedAll(List.of(query)).get(0);
        float[] desc = descVector(collection, query);
        SparseVectorizer.SparseVector sparse = sparseVectorizer.vectorizeCode(query);
        int candidateLimit = Math.max(limit * resolvedCandidateMultiplier(), MIN_CANDIDATE_LIMIT);
        int prefetchLimit = candidateLimit * CANDIDATE_MULTIPLIER;
        List<CodeChunk> candidates = fusedCandidates(collection, dense, desc, sparse, projectId,
                candidateLimit, prefetchLimit, null);
        List<CodeChunk> rerankInput = codeBgeRerankEnabled() ? semanticRerank(query, candidates) : candidates;
        List<CodeChunk> ranked = rerankCandidatesInternal(query, rerankInput, limit,
                properties.retrieval().resolvedCodeQueryExpansionEnabled(),
                properties.retrieval().resolvedCodeStructuralRerankEnabled());
        return new CodeSearchTrace(candidates, ranked,
                prefetchOnly(collection, dense, projectId, prefetchLimit),
                prefetchOnly(collection, sparse, projectId, prefetchLimit));
    }

    /** 生产路径：仅执行一次 fused 查询，不收集 prefetch 归因列表。 */
    private List<CodeChunk> fusedSearch(String collection, String query, String projectId, int limit) {
        ensureCollection(collection);
        float[] dense = embeddingBatcher.embedAll(List.of(query)).get(0);
        float[] desc = descVector(collection, query);
        SparseVectorizer.SparseVector sparse = sparseVectorizer.vectorizeCode(query);
        int candidateLimit = Math.max(limit * resolvedCandidateMultiplier(), MIN_CANDIDATE_LIMIT);
        List<CodeChunk> candidates = fusedCandidates(collection, dense, desc, sparse, projectId,
                candidateLimit, candidateLimit * CANDIDATE_MULTIPLIER, null);
        List<CodeChunk> rerankInput = codeBgeRerankEnabled() ? semanticRerank(query, candidates) : candidates;
        return rerankCandidatesInternal(query, rerankInput, limit,
                properties.retrieval().resolvedCodeQueryExpansionEnabled(),
                properties.retrieval().resolvedCodeStructuralRerankEnabled());
    }

    /**
     * 类名限定召回：只在指定类文件范围内做混合检索，用于「在 XxxService 中由哪个方法实现」类查询。
     * 与全局混合检索共用一次向量计算（embedding 是主要延迟）。
     *
     * <p>策略：先做全局检索（与纯混合检索完全一致）。仅当全局精排结果已足够回答查询时才走快速路径：
     * 目标符号名未给出时要求全局已含目标类的方法/构造器；给出目标符号名时还要求该符号出现在
     * 全局目标类方法中（否则全局只有同名/近似方法的错误答案，必须走类内补召回）。
     * 快速路径返回前做目标类方法优先稳定重排——查询点名类本体时，容器类 chunk 不是答案；
     * 否则做类文件范围内查询并把类内候选作为并集补齐（全局顺序优先、类内只补召回），
     * 统一结构重排后同样方法优先。</p>
     *
     * @param targetSymbolName 查询解析出的方法名（可能为 null，类名限定查询无方法名）
     */
    public ScopedSearchResult searchWithClassScope(String collection, String query, String projectId,
                                                   List<String> classFiles, String targetSymbolName, int limit) {
        ensureCollection(collection);
        float[] dense = embeddingBatcher.embedAll(List.of(query)).get(0);
        float[] desc = descVector(collection, query);
        SparseVectorizer.SparseVector sparse = sparseVectorizer.vectorizeCode(query);
        int candidateLimit = Math.max(limit * resolvedCandidateMultiplier(), MIN_CANDIDATE_LIMIT);
        int prefetchLimit = candidateLimit * CANDIDATE_MULTIPLIER;

        List<CodeChunk> globalCandidates = fusedCandidates(collection, dense, desc, sparse, projectId,
                candidateLimit, prefetchLimit, null);
        List<CodeChunk> globalRerankInput = codeBgeRerankEnabled()
                ? semanticRerank(query, globalCandidates) : globalCandidates;
        List<CodeChunk> global = rerankCandidatesInternal(query, globalRerankInput, limit,
                properties.retrieval().resolvedCodeQueryExpansionEnabled(),
                properties.retrieval().resolvedCodeStructuralRerankEnabled());
        if (answersQuery(global, classFiles, targetSymbolName)) {
            return new ScopedSearchResult(global, methodFirst(global, classFiles, limit), globalCandidates);
        }

        List<CodeChunk> classCandidates = fusedCandidates(collection, dense, desc, sparse, projectId,
                candidateLimit, prefetchLimit, classFiles);
        if (classCandidates.isEmpty() || !suppliesTargetSymbol(classCandidates, classFiles, targetSymbolName)) {
            // 类内无法提供目标符号（如解析器误把业务文本中的标识符当方法名，或类文件无索引）：
            // 类内并集只会扰动排序而无召回收益，按无符号类名限定处理（快速路径或纯全局精排）
            if (answersQuery(global, classFiles, null)) {
                return new ScopedSearchResult(global, methodFirst(global, classFiles, limit), globalCandidates);
            }
            return new ScopedSearchResult(global, global, globalCandidates);
        }
        List<CodeChunk> union = unionCandidates(globalCandidates, classCandidates);
        List<CodeChunk> unionRerankInput = codeBgeRerankEnabled()
                ? semanticRerank(query, union) : union;
        List<CodeChunk> unionRanked = rerankCandidatesInternal(query, unionRerankInput, limit,
                properties.retrieval().resolvedCodeQueryExpansionEnabled(),
                properties.retrieval().resolvedCodeStructuralRerankEnabled());
        // candidates = 实际重排输入的并集候选池（含类内补齐），保证归因与精排同源
        return new ScopedSearchResult(global, methodFirst(unionRanked, classFiles, limit), union);
    }

    /**
     * 全局精排结果是否已足以回答查询：目标符号名给出时必须命中该符号的目标类方法；
     * 未给出时（类名限定查询）要求至少存在目标类方法/构造器。
     */
    private static boolean answersQuery(List<CodeChunk> ranked, List<String> classFiles, String targetSymbolName) {
        boolean hasClassMethod = ranked.stream().anyMatch(chunk -> classFiles.contains(chunk.filePath())
                && ("method".equals(chunk.symbolType()) || "constructor".equals(chunk.symbolType())));
        if (!hasClassMethod) {
            return false;
        }
        if (targetSymbolName == null || targetSymbolName.isBlank()) {
            return true;
        }
        return ranked.stream().anyMatch(chunk -> classFiles.contains(chunk.filePath())
                && targetSymbolName.equalsIgnoreCase(chunk.symbolName()));
    }

    /** 类内候选池是否包含目标符号（targetSymbolName 为 null 时视为可提供）。 */
    private static boolean suppliesTargetSymbol(List<CodeChunk> classCandidates, List<String> classFiles,
                                                String targetSymbolName) {
        if (targetSymbolName == null || targetSymbolName.isBlank()) {
            return true;
        }
        return classCandidates.stream().anyMatch(chunk -> classFiles.contains(chunk.filePath())
                && targetSymbolName.equalsIgnoreCase(chunk.symbolName()));
    }

    /** 候选并集：全局 RRF 候选顺序优先，类内候选按 filePath+symbolName+startLine 去重后补齐（只补召回，不覆盖全局顺序）。 */
    static List<CodeChunk> unionCandidates(List<CodeChunk> global, List<CodeChunk> classScoped) {
        java.util.Map<String, CodeChunk> seen = new LinkedHashMap<>();
        for (CodeChunk chunk : global == null ? List.<CodeChunk>of() : global) {
            seen.putIfAbsent(candidateKey(chunk), chunk);
        }
        for (CodeChunk chunk : classScoped == null ? List.<CodeChunk>of() : classScoped) {
            seen.putIfAbsent(candidateKey(chunk), chunk);
        }
        return List.copyOf(seen.values());
    }

    private static String candidateKey(CodeChunk chunk) {
        return chunk.filePath() + '\n' + chunk.symbolName() + '\n' + chunk.startLine();
    }

    /**
     * 类名限定召回结果：全局混合检索精排、最终结果（目标类方法优先，可能含类内召回补齐）、
     * 以及本次检索的实际重排输入候选池（供离线诊断归因，与最终结果来自同一次检索）。
     */
    public record ScopedSearchResult(List<CodeChunk> global, List<CodeChunk> classScoped,
                                     List<CodeChunk> candidates) {
    }

    /**
     * 稳定地把目标类文件范围内的方法/构造器 chunk 移到其余候选之前（其余顺序不变，类名限定召回专用）。
     * 只提升目标类内的方法：其他类的方法与容器 chunk 保持原有相对顺序，避免无关类方法被误提权。
     */
    static List<CodeChunk> methodFirst(List<CodeChunk> ranked, List<String> classFiles, int limit) {
        List<CodeChunk> classMethods = new ArrayList<>();
        List<CodeChunk> others = new ArrayList<>();
        for (CodeChunk chunk : ranked) {
            if (classFiles.contains(chunk.filePath())
                    && ("method".equals(chunk.symbolType()) || "constructor".equals(chunk.symbolType()))) {
                classMethods.add(chunk);
            }
            else {
                others.add(chunk);
            }
        }
        List<CodeChunk> merged = new ArrayList<>(classMethods);
        merged.addAll(others);
        return merged.size() <= limit ? List.copyOf(merged) : merged.subList(0, limit);
    }

    /** 业务语义检索向量：意图别名增强后的查询文本嵌入；collection 不支持 desc_dense 时返回 null。 */
    private float[] descVector(String collection, String query) {
        if (!supportsDescDense(collection)) {
            return null;
        }
        String descQuery = properties.retrieval() != null && properties.retrieval().resolvedCodeQueryExpansionEnabled()
                ? expandQuery(query) : query;
        return embeddingBatcher.embedAll(List.of(descQuery)).get(0);
    }

    /** 执行 dense+desc+sparse 三预取 + RRF 融合查询，返回候选代码 chunk。desc 路仅在 collection 支持时启用。
     *  fileScope 非空时追加文件路径范围过滤（仅类名限定召回使用）。 */
    private List<CodeChunk> fusedCandidates(String collection, float[] dense, float[] desc,
                                            SparseVectorizer.SparseVector sparse, String projectId,
                                            int candidateLimit, int prefetchLimit, List<String> fileScope) {
        Map<String, Object> projectFilter = fileScope == null || fileScope.isEmpty()
                ? filter(projectId)
                : fileScopeFilter(projectId, fileScope);
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> prefetches = new ArrayList<>();
        prefetches.add(Map.of("query", dense, "using", "dense", "limit", prefetchLimit, "filter", projectFilter));
        if (desc != null && supportsDescDense(collection)) {
            prefetches.add(Map.of("query", desc, "using", "desc_dense", "limit", prefetchLimit, "filter", projectFilter));
        }
        prefetches.add(Map.of("query", Map.of("indices", sparse.indices(), "values", sparse.values()),
                "using", "sparse", "limit", prefetchLimit, "filter", projectFilter));
        body.put("prefetch", prefetches);
        body.put("query", Map.of("fusion", "rrf"));
        body.put("limit", candidateLimit);
        body.put("with_payload", true);
        Map<String, Object> response = executeIdempotentQuery(() -> client.post()
                .uri("/collections/{collection}/points/query", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {}));
        return extractPoints(response);
    }

    /** 判断 collection 是否带 desc_dense 命名空间（每个 collection 只探测一次，失败视为不支持）。 */
    private boolean supportsDescDense(String collection) {
        if (descDenseChecked.contains(collection)) {
            return descDenseCollections.contains(collection);
        }
        boolean supported = false;
        try {
            Map<String, Object> info = client.get().uri("/collections/{collection}", collection).retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            Map<String, Object> result = map(info == null ? null : info.get("result"));
            Map<String, Object> config = map(result.get("config"));
            Map<String, Object> params = map(config.get("params"));
            Map<String, Object> vectors = map(params.get("vectors"));
            supported = vectors.containsKey("desc_dense");
        } catch (RuntimeException exception) {
            LOGGER.warn("Unable to inspect collection {} for desc_dense: {}", collection, exception.getMessage());
        }
        descDenseChecked.add(collection);
        if (supported) {
            descDenseCollections.add(collection);
        }
        return supported;
    }

    /** 仅用于离线评测归因：单独查询 dense / sparse 预取结果，不参与生产检索路径。 */
    private List<CodeChunk> prefetchOnly(String collection, float[] dense, String projectId, int prefetchLimit) {
        Map<String, Object> response = executeIdempotentQuery(() -> client.post()
                .uri("/collections/{collection}/points/query", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "query", dense, "using", "dense", "limit", prefetchLimit,
                        "filter", filter(projectId), "with_payload", true))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {}));
        return extractPoints(response);
    }

    private List<CodeChunk> prefetchOnly(String collection, SparseVectorizer.SparseVector sparse,
                                         String projectId, int prefetchLimit) {
        Map<String, Object> response = executeIdempotentQuery(() -> client.post()
                .uri("/collections/{collection}/points/query", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "query", Map.of("indices", sparse.indices(), "values", sparse.values()),
                        "using", "sparse", "limit", prefetchLimit,
                        "filter", filter(projectId), "with_payload", true))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {}));
        return extractPoints(response);
    }

    private boolean codeBgeRerankEnabled() {
        return bgeReranker != null && properties.retrieval() != null
                && properties.retrieval().resolvedCodeBgeRerankEnabled();
    }

    private int resolvedCandidateMultiplier() {
        return properties.retrieval() == null ? CANDIDATE_MULTIPLIER
                : properties.retrieval().resolvedCodeCandidateMultiplier();
    }

    /** BGE 语义重排 RRF 候选；先按 RRF 名次剪到 bgeTopK 再计分（CPU 计分成本 ~1s/文本），服务不可用时回退原始顺序。 */
    List<CodeChunk> semanticRerank(String query, List<CodeChunk> candidates) {
        if (candidates.isEmpty() || bgeReranker == null) {
            return candidates;
        }
        try {
            int topK = Math.min(properties.retrieval().resolvedBgeTopK(), candidates.size());
            List<CodeChunk> pruned = candidates.size() <= topK ? candidates : candidates.subList(0, topK);
            List<ChunkRecord> adapted = pruned.stream().map(CodeQdrantStore::toRerankCandidate).toList();
            List<ChunkRecord> reranked = bgeReranker.rerank(query, adapted, topK);
            Map<String, CodeChunk> byId = new LinkedHashMap<>();
            for (CodeChunk candidate : candidates) {
                byId.put(candidate.id(), candidate);
            }
            List<CodeChunk> result = new ArrayList<>();
            for (ChunkRecord chunk : reranked) {
                CodeChunk match = byId.get(chunk.id());
                if (match != null) {
                    result.add(match);
                }
            }
            return result.isEmpty() ? candidates : result;
        } catch (RuntimeException exception) {
            LOGGER.warn("Code BGE rerank unavailable, using RRF order: {}", exception.getMessage());
            return candidates;
        }
    }

    /** 把代码 chunk 适配为 BGE 契约要求的 ChunkRecord，childText 复用向量检索文本。 */
    private static ChunkRecord toRerankCandidate(CodeChunk chunk) {
        return new ChunkRecord(chunk.id(), chunk.projectId(), chunk.commitSha(), chunk.filePath(),
                null, "", retrievalText(chunk), chunk.contentHash(), chunk.startLine(), chunk.endLine());
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


    /** 按指定批量大小切分 chunk 列表，便于分批写入。 */
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

    /** 为每个 chunk 计算 dense/desc/sparse 向量，组装成 Qdrant 点（含完整 payload 元数据）。 */
    private List<Map<String, Object>> buildPoints(List<CodeChunk> chunks) {
        List<String> retrievalTexts = chunks.stream().map(CodeQdrantStore::retrievalText).toList();
        List<String> denseRetrievalTexts = chunks.stream().map(CodeQdrantStore::denseRetrievalText).toList();
        List<float[]> denseVectors = embeddingBatcher.embedAll(denseRetrievalTexts);
        List<String> descTexts = chunks.stream().map(this::descSearchText).toList();
        List<float[]> descVectors = embeddingBatcher.embedAll(descTexts);
        List<Map<String, Object>> points = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            CodeChunk chunk = chunks.get(index);
            SparseVectorizer.SparseVector sparse = sparseVectorizer.vectorizeCode(retrievalTexts.get(index));
            Map<String, Object> vectors = new LinkedHashMap<>();
            vectors.put("dense", denseVectors.get(index));
            if (descVectors != null && index < descVectors.size()) {
                vectors.put("desc_dense", descVectors.get(index));
            }
            vectors.put("sparse", Map.of("indices", sparse.indices(), "values", sparse.values()));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("projectId", chunk.projectId());
            payload.put("commitSha", chunk.commitSha());
            payload.put("filePath", chunk.filePath());
            payload.put("symbolType", chunk.symbolType());
            payload.put("symbolName", chunk.symbolName());
            payload.put("language", chunk.language());
            payload.put("startLine", chunk.startLine());
            payload.put("endLine", chunk.endLine());
            payload.put("text", chunk.text());
            payload.put("contentHash", chunk.contentHash());
            payload.put("className", chunk.className());
            payload.put("module", chunk.module());
            payload.put("layer", chunk.layer());
            payload.put("businessDescCn", chunk.businessDescCn());
            payload.put("businessDescEn", chunk.businessDescEn());
            payload.put("keywords", chunk.keywords());
            payload.put("userQuestions", chunk.userQuestions());
            payload.put("synonyms", chunk.synonyms());
            points.add(Map.of(
                    "id", chunk.id(),
                    "vector", vectors,
                    "payload", payload));
        }
        return points;
    }

    /**
     * 业务语义向量的输入文本：类名、方法名、模块、业务描述（中/英）、关键词、同义词、用户问题。
     * 不含源码——让 embedding 专注编码自然语言语义。
     */
    private String descSearchText(CodeChunk chunk) {
        StringBuilder sb = new StringBuilder();
        String className = safeText(chunk.className());
        if (!className.isBlank()) {
            sb.append('[').append(className).append("] ");
        }
        sb.append(safeText(chunk.symbolName())).append(" (").append(safeText(chunk.symbolType())).append(")\n");
        if (!safeText(chunk.module()).isBlank()) {
            sb.append("模块: ").append(chunk.module()).append('\n');
        }
        if (!safeText(chunk.businessDescCn()).isBlank()) {
            sb.append(chunk.businessDescCn()).append('\n');
        }
        if (!safeText(chunk.businessDescEn()).isBlank()) {
            sb.append(chunk.businessDescEn()).append('\n');
        }
        if (!chunk.keywords().isEmpty()) {
            sb.append(String.join(" ", chunk.keywords())).append('\n');
        }
        if (!chunk.synonyms().isEmpty()) {
            sb.append(String.join(" ", chunk.synonyms())).append('\n');
        }
        if (!chunk.userQuestions().isEmpty()) {
            sb.append(String.join(" ", chunk.userQuestions())).append('\n');
        }
        return sb.toString();
    }

    /** 标注缓存条目：LLM 生成的语义元数据，供重索引时跳过未变更代码。 */
    public record AnnotationEntry(String businessDescCn, String businessDescEn,
                                   List<String> keywords, List<String> userQuestions,
                                   List<String> synonyms) {
        public AnnotationEntry {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            userQuestions = userQuestions == null ? List.of() : List.copyOf(userQuestions);
            synonyms = synonyms == null ? List.of() : List.copyOf(synonyms);
        }
    }

    /** 计算源码文本的 SHA-256 摘要，作为标注缓存的键。 */
    static String sourceHash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /**
     * 从 Qdrant 加载已有标注数据构建缓存。
     * key = sourceHash(text)，即源码内容的 SHA-256 摘要。
     * 用于重索引时跳过未变更代码的 LLM 标注。
     */
    public Map<String, AnnotationEntry> fetchAnnotationCache(String collection, String projectId) {
        try {
            client.get().uri("/collections/{collection}", collection).retrieve().toBodilessEntity();
        } catch (RuntimeException exception) {
            LOGGER.info("Collection {} 不存在，跳过标注缓存加载", collection);
            return Map.of();
        }

        Map<String, AnnotationEntry> cache = new HashMap<>();
        Object offset = null;
        int page = 0;

        while (true) {
            page++;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("filter", filter(projectId));
            body.put("limit", 100);
            body.put("with_payload", Map.of("include",
                    List.of("text", "businessDescCn", "businessDescEn", "keywords",
                            "userQuestions", "synonyms")));
            if (offset != null) {
                body.put("offset", offset);
            }

            Map<String, Object> response;
            try {
                response = client.post()
                        .uri("/collections/{collection}/points/scroll", collection)
                        .contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
                        .body(new ParameterizedTypeReference<>() {});
            } catch (RuntimeException exception) {
                LOGGER.info("读取标注缓存失败，使用空缓存: {}", exception.getMessage());
                return Map.of();
            }
            if (response == null) break;
            Map<String, Object> result = map(response.get("result"));
            List<Object> points = list(result.get("points"));

            for (Object p : points) {
                Map<String, Object> point = map(p);
                Map<String, Object> payload = map(point.get("payload"));
                String text = string(payload, "text");
                String descCn = string(payload, "businessDescCn");
                if (!text.isBlank() && !descCn.isBlank()) {
                    cache.putIfAbsent(sourceHash(text), new AnnotationEntry(
                            descCn, string(payload, "businessDescEn"),
                            stringList(payload, "keywords"),
                            stringList(payload, "userQuestions"),
                            stringList(payload, "synonyms")));
                }
            }

            offset = result.get("next_page_offset");
            if (offset == null || points.isEmpty()) break;
            if (page % 20 == 0) {
                LOGGER.info("加载标注缓存: {} 页, {} 条", page, cache.size());
            }
        }
        LOGGER.info("标注缓存加载完成: {} 条记录", cache.size());
        return cache;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : items) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
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
     * 兼容入口：不启用 0.8.5 结构化重排增强信号（旧行为），供既有单元测试与消融基线使用。
     */
    static List<CodeChunk> rerankCandidates(String query, List<CodeChunk> candidates, int limit) {
        return rerankCandidates(query, candidates, limit, true, false);
    }

    /**
     * 在有限 RRF 候选池内做确定性结构重排：原始名次仍是主信号，对符号词与代码角色意图加分；
     * structural 为 true 时追加类名/限定名/文件名精确匹配信号（0.8.5 增强）。
     */
    static List<CodeChunk> rerankCandidates(String query, List<CodeChunk> candidates, int limit,
                                            boolean queryExpansionEnabled, boolean structural) {
        CodeQueryAnalyzer analyzer = new CodeQueryAnalyzer();
        return rerankCandidates(query, candidates, limit, queryExpansionEnabled, structural, analyzer);
    }

    /** 实例路径：复用注入的查询解析器，避免重复构造。 */
    private List<CodeChunk> rerankCandidatesInternal(String query, List<CodeChunk> candidates, int limit,
                                                     boolean queryExpansionEnabled, boolean structural) {
        CodeQueryAnalyzer analyzer = queryAnalyzer == null ? new CodeQueryAnalyzer() : queryAnalyzer;
        return rerankCandidates(query, candidates, limit, queryExpansionEnabled, structural, analyzer);
    }

    private static List<CodeChunk> rerankCandidates(String query, List<CodeChunk> candidates, int limit,
                                                    boolean queryExpansionEnabled, boolean structural,
                                                    CodeQueryAnalyzer analyzer) {
        if (candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        String rankingQuery = queryExpansionEnabled ? expandQuery(query) : query;
        CodeQueryAnalyzer.ParsedCodeQuery parsed = structural ? analyzer.parse(query)
                : CodeQueryAnalyzer.ParsedCodeQuery.GENERIC;
        List<RankedCodeChunk> ranked = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            CodeChunk chunk = candidates.get(index);
            ranked.add(new RankedCodeChunk(chunk, index, candidateScore(rankingQuery, parsed, chunk, index,
                    candidates.size(), structural), exactMatchLevel(chunk, parsed, structural)));
        }
        return ranked.stream()
                .sorted(Comparator.comparingDouble(RankedCodeChunk::score).reversed()
                        .thenComparing(RankedCodeChunk::exactMatchLevel, Comparator.reverseOrder())
                        .thenComparingInt(RankedCodeChunk::originalRank)
                        .thenComparing(chunk -> safeText(chunk.chunk().filePath()))
                        .thenComparingInt(chunk -> chunk.chunk().startLine()))
                .limit(Math.min(limit, ranked.size()))
                .map(RankedCodeChunk::chunk)
                .toList();
    }

    /** 结构打分：以 RRF 原始名次为基础分，对符号名命中、类名/限定名命中、服务实现/控制器/测试等代码角色意图加权。 */
    private static double candidateScore(String query, CodeQueryAnalyzer.ParsedCodeQuery parsed,
                                         CodeChunk chunk, int index, int candidateCount, boolean structural) {
        String normalizedQuery = safeText(query).toLowerCase(java.util.Locale.ROOT);
        String normalizedSymbol = safeText(chunk.symbolName()).toLowerCase(java.util.Locale.ROOT);
        String symbolTerms = splitIdentifier(chunk.symbolName()).toLowerCase(java.util.Locale.ROOT);
        String path = safeText(chunk.filePath()).toLowerCase(java.util.Locale.ROOT);
        String chunkClass = chunkClassName(chunk);
        double score = 1.0 - (0.30 * index / Math.max(candidateCount - 1.0, 1.0));
        int exactMatchLevel = 0;

        String parsedClassName = structural ? parsed.className() : null;
        String parsedSymbolName = structural ? parsed.symbolName() : null;
        boolean classNameMatch = parsedClassName != null
                && (parsedClassName.equalsIgnoreCase(chunkClass)
                || parsedClassName.equalsIgnoreCase(safeText(chunk.className())));
        boolean symbolNameMatch = parsedSymbolName != null
                && parsedSymbolName.equalsIgnoreCase(normalizedSymbol);
        boolean filePathMatch = structural && parsed.filePath() != null
                && (safeText(chunk.filePath()).equalsIgnoreCase(parsed.filePath())
                || safeText(chunk.filePath()).toLowerCase(java.util.Locale.ROOT)
                .endsWith("/" + parsed.filePath().toLowerCase(java.util.Locale.ROOT)));

        if (structural) {
            if (classNameMatch) {
                score += 0.80;
                exactMatchLevel = 1;
            }
            if (symbolNameMatch) {
                score += 0.50;
                if (classNameMatch) {
                    score += 0.20; // 完整 ClassName.methodName 命中合计 +1.50
                    exactMatchLevel = 2;
                }
            }
            if (filePathMatch) {
                score += 0.60; // 查询显式给出文件路径：同名类场景下精确区分文件
                if (exactMatchLevel == 0) {
                    exactMatchLevel = 1;
                }
            }
            if (!classNameMatch && parsedClassName != null
                    && fileNameWithoutExtension(chunk).equalsIgnoreCase(parsedClassName)) {
                score += 0.50; // 文件名与类名一致
            }
        }
        if (!symbolNameMatch && !normalizedSymbol.isBlank()
                && compact(normalizedQuery).contains(compact(normalizedSymbol))) {
            // 旧规则兜底：查询含完整方法名（含解析器未提取到的全小写方法名如 handle）
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

    /** 类名信号：payload className 优先，缺失时回退文件基名（Java 类文件基名即类名）。 */
    private static String chunkClassName(CodeChunk chunk) {
        String className = safeText(chunk.className());
        return className.isBlank() ? fileNameWithoutExtension(chunk) : className;
    }

    /** 候选与解析查询的精确匹配层级：2=类名+方法名、1=仅类名或文件路径精确命中、0=无。 */
    private static int exactMatchLevel(CodeChunk chunk, CodeQueryAnalyzer.ParsedCodeQuery parsed, boolean structural) {
        if (!structural || parsed.kind() == CodeQueryAnalyzer.QueryKind.GENERIC) {
            return 0;
        }
        boolean classNameMatch = parsed.className() != null
                && parsed.className().equalsIgnoreCase(chunkClassName(chunk));
        boolean symbolNameMatch = parsed.symbolName() != null
                && parsed.symbolName().equalsIgnoreCase(safeText(chunk.symbolName()));
        boolean filePathMatch = parsed.filePath() != null
                && (safeText(chunk.filePath()).equalsIgnoreCase(parsed.filePath())
                || safeText(chunk.filePath()).toLowerCase(java.util.Locale.ROOT)
                .endsWith("/" + parsed.filePath().toLowerCase(java.util.Locale.ROOT)));
        if (classNameMatch && symbolNameMatch) {
            return 2;
        }
        return (classNameMatch || filePathMatch) ? 1 : 0;
    }

    private static String fileNameWithoutExtension(CodeChunk chunk) {
        String filePath = safeText(chunk.filePath());
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        String base = slash >= 0 ? filePath.substring(slash + 1) : filePath;
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    /** 内置中英文意图别名，classpath 中的 code-query-aliases.json 可追加或覆盖。 */
    private static final Map<String, String> QUERY_ALIASES = loadQueryAliases();

    /** 将查询命中的中英文意图别名追加到查询文本，增强跨语言检索召回。 */
    static String expandQuery(String query) {
        StringBuilder expanded = new StringBuilder(safeText(query));
        for (Map.Entry<String, String> alias : QUERY_ALIASES.entrySet()) {
            appendAlias(expanded, query, alias.getKey(), alias.getValue());
        }
        return expanded.toString();
    }

    /** 加载查询别名：先放入内置中英文意图别名，再加载 classpath 中的 code-query-aliases.json 覆盖/追加；外部文件无效时忽略。 */
    private static Map<String, String> loadQueryAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("流式对话", "chat stream");
        aliases.put("普通对话", "chat");
        aliases.put("AI 对话", "chat");
        aliases.put("搜索摘要", "search summary");
        aliases.put("用户搜索", "search user");
        aliases.put("笔记搜索", "search note");
        aliases.put("粉丝列表", "find fans list");
        aliases.put("关注列表", "find following list");
        aliases.put("子评论", "child comment");
        aliases.put("一级评论", "comment page list");
        aliases.put("取消点赞", "unlike");
        aliases.put("文件上传", "upload file");
        aliases.put("推荐", "recommend");
        aliases.put("热门", "trending");
        try (InputStream in = CodeQdrantStore.class.getResourceAsStream(ALIAS_RESOURCE)) {
            if (in != null) {
                Map<String, String> external = JSON.readValue(in, new TypeReference<Map<String, String>>() {});
                if (external != null) {
                    aliases.putAll(external);
                }
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Ignoring invalid {}; using built-in query aliases", ALIAS_RESOURCE, exception);
        }
        return Map.copyOf(aliases);
    }

    private static void appendAlias(StringBuilder expanded, String query, String phrase, String alias) {
        if (query.contains(phrase.toLowerCase(java.util.Locale.ROOT))) {
            expanded.append(' ').append(alias);
        }
    }

    private static boolean asksForServiceImplementation(String query) {
        return containsAny(query, "服务实现", "实现入口", "业务逻辑", "service implementation",
                "implementation entry", "business logic");
    }

    private static boolean asksForBusinessBehavior(String query) {
        return asksForServiceImplementation(query) || containsAny(query, "如何", "保证", "触发", "同步",
                "生成", "处理", "上传", "签发", "限制", "缓存", "排序", "去重", "返回",
                "搜索", "查询", "对话", "摘要", "列表", "推荐",
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

    private record RankedCodeChunk(CodeChunk chunk, int originalRank, double score, int exactMatchLevel) {
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
     * Qdrant 查询走 POST 但语义只读，客户端侧瞬时 IO 失败后安全重试一次；
     * HTTP 状态错误与序列化契约错误刻意不重试。
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

    /**
     * 确保 collection 已存在：不存在则按 dense（Cosine）+ sparse（idf）配置创建；
     * 连接失败时按退避间隔重试，最多 10 次后抛出异常。
     */
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
                            "vectors", Map.of(
                                    "dense", Map.of("size", embeddingModel.dimensions(), "distance", "Cosine"),
                                    "desc_dense", Map.of("size", embeddingModel.dimensions(), "distance", "Cosine")),
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

    /** 删除某项目在该 collection 中的全部点（替换写入前使用）。 */
    private void deleteProject(String collection, String projectId) {
        client.post().uri("/collections/{collection}/points/delete?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("filter", filter(projectId)))
                .retrieve().toBodilessEntity();
    }

    private Map<String, Object> filter(String projectId) {
        return Map.of("must", List.of(Map.of("key", "projectId", "match", Map.of("value", projectId))));
    }

    /** 文件范围过滤：projectId 精确匹配 + filePath 命中任一目标文件（Qdrant 多值匹配使用 match.any）。 */
    private Map<String, Object> fileScopeFilter(String projectId, List<String> filePaths) {
        return Map.of("must", List.of(
                Map.of("key", "projectId", "match", Map.of("value", projectId)),
                Map.of("key", "filePath", "match", Map.of("any", filePaths))));
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

    /** 将 Qdrant 返回的点负载反序列化为 CodeChunk；language 为空时按文件路径后缀推断，className 随载荷保留。 */
    private CodeChunk toChunk(Object raw) {
        Map<String, Object> point = map(raw);
        Map<String, Object> p = map(point.get("payload"));
        return new CodeChunk(String.valueOf(point.get("id")), string(p, "projectId"), string(p, "commitSha"),
                string(p, "filePath"), string(p, "symbolType"), string(p, "symbolName"),
                integer(p, "startLine"), integer(p, "endLine"), string(p, "text"), string(p, "contentHash"),
                language(p), string(p, "className"), string(p, "module"), string(p, "layer"),
                string(p, "businessDescCn"), string(p, "businessDescEn"), List.of(), List.of(), List.of(),
                List.of(), "", List.of(), List.of());
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

    /** 可复现检索评测用的有界检索阶段结果：RRF 候选、最终精排结果及 dense/sparse 各自的预取归因。 */
    public record CodeSearchTrace(List<CodeChunk> candidates, List<CodeChunk> ranked,
                                  List<CodeChunk> denseCandidates, List<CodeChunk> sparseCandidates) {
        public CodeSearchTrace {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            ranked = ranked == null ? List.of() : List.copyOf(ranked);
            denseCandidates = denseCandidates == null ? List.of() : List.copyOf(denseCandidates);
            sparseCandidates = sparseCandidates == null ? List.of() : List.copyOf(sparseCandidates);
        }

        /** 兼容旧调用方的构造器：不提供 dense/sparse 预取归因结果。 */
        public CodeSearchTrace(List<CodeChunk> candidates, List<CodeChunk> ranked) {
            this(candidates, ranked, List.of(), List.of());
        }
    }
}
