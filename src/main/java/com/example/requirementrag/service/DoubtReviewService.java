package com.example.requirementrag.service;

import com.example.requirementrag.model.DoubtBatch;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.RequirementDoubt;
import com.example.requirementrag.model.ReviewRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.rerank.BgeReranker;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.observability.RagObservability;
import com.example.requirementrag.knowledge.HistoricalDoubtService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 基于 Qdrant 混合检索与 BGE/LLM 重排的存疑评审服务。
 */
@Service
public class DoubtReviewService {

    private static final int MAX_CONTEXT_CHARS = 80_000;

    private static final String QUESTION_STYLE = """
            输出格式要求：
            - feature（功能点）：2-8 字
            - sourceLocation（细化描述）：一句话概括文档相关内容；文档未写清的填「需求未提及但开发必须知道」
            - question（存疑/问题）：口语化短问句，不超过 30 字，只写核心决策点，禁止长段解释和「文档未说明」套话
            """;

    private static final String CURRENT_SYSTEM_PROMPT = """
            你是一名资深游戏需求评审人员。请仅针对 5.1 产品文档，输出恰好 %d 条待产品确认的存疑。

            规则：
            - 问题必须来自 5.1 产品文档正文（5.1/ 目录）。
            - 对照历史存疑去重：已在旧版本问过且已有产品解答的，不再重复追问。
            - 5.1 文档可能不完整，很多规则此前已口述确认；若历史记录已覆盖同类问题，跳过即可。
            - 每条只包含一个需要产品决策的问题。
            %s
            """;

    private static final String CURRENT_USER_PROMPT = """
            文档ID：%s
            版本：%s
            限定模块：%s

            历史版本存疑（仅用于去重）：
            ---
            %s
            ---

            5.1 产品文档正文：
            ---
            %s
            ---

            5.1 检索补充片段：
            ---
            %s
            ---

            请输出恰好 %d 条 5.1 新问题。
            """;

    private static final String PRIOR_SYSTEM_PROMPT = """
            你是一名资深游戏需求评审人员。请从历史版本存疑中，输出恰好 %d 条「以前版本存疑」。

            规则：
            - 优先选择历史记录中尚无产品解答、或解答仍不充分的问题。
            - 可与 5.1 仍相关，但问题本身属于旧版本遗留，不是 5.1 新文档中新发现的问题。
            - 不得重复已有明确产品解答的历史问题。
            - 模块名前缀使用 [历史版本]。
            %s
            """;

    private static final String PRIOR_USER_PROMPT = """
            当前版本：%s
            限定模块：%s

            历史版本存疑（含产品解答）：
            ---
            %s
            ---

            请输出恰好 %d 条以前版本遗留存疑。
            """;

    private final ChatClient chatClient;
    private final QdrantHybridStore store;
    private final BgeReranker bgeReranker;
    private final RagProperties properties;
    private final ProjectRegistry projectRegistry;
    private final QueryRouter queryRouter;
    private final RagObservability observability;
    private final HistoricalDoubtService historicalDoubtService;

    public DoubtReviewService(ChatClient chatClient, QdrantHybridStore store, BgeReranker bgeReranker,
                              RagProperties properties, ProjectRegistry projectRegistry,
                              QueryRouter queryRouter,
                              RagObservability observability,
                              HistoricalDoubtService historicalDoubtService) {
        this.chatClient = chatClient;
        this.store = store;
        this.bgeReranker = bgeReranker;
        this.properties = properties;
        this.projectRegistry = projectRegistry;
        this.queryRouter = queryRouter;
        this.observability = observability;
        this.historicalDoubtService = historicalDoubtService;
    }

    /**
     * 检索 5.1 文档上下文并生成当前版本新存疑。
     */
    public DoubtBatch reviewCurrentVersion(ReviewRequest request) {
        RetrievalContext context = loadRetrievalContext(request);
        String historicalContext = loadHistoricalContext(request.projectId());
        int count = properties.review().currentVersionQuestions();

        DoubtBatch generated = observability.observe("llm.generate.current", request.documentId(), request.version(),
                () -> chatClient.prompt()
                        .system(CURRENT_SYSTEM_PROMPT.formatted(count, QUESTION_STYLE))
                        .user(CURRENT_USER_PROMPT.formatted(
                                request.documentId(),
                                request.version(),
                                Objects.toString(request.module(), "全部"),
                                historicalContext,
                                context.latestContext(),
                                context.retrievedContext(),
                                count))
                        .options(OpenAiChatOptions.builder()
                                .model(properties.llm().generationModel())
                                .temperature(0.1))
                        .call()
                        .entity(DoubtBatch.class, spec -> spec.validateSchema()));

        return limitQuestions(normalize(generated, request.module()), count);
    }

    /**
     * 从历史存疑中生成以前版本遗留问题。
     */
    public DoubtBatch reviewPriorVersion(ReviewRequest request) {
        String historicalContext = loadHistoricalContext(request.projectId());
        int count = properties.review().priorVersionQuestions();

        DoubtBatch generated = observability.observe("llm.generate.prior", request.documentId(), request.version(),
                () -> chatClient.prompt()
                        .system(PRIOR_SYSTEM_PROMPT.formatted(count, QUESTION_STYLE))
                        .user(PRIOR_USER_PROMPT.formatted(
                                request.version(),
                                Objects.toString(request.module(), "全部"),
                                historicalContext,
                                count))
                        .options(OpenAiChatOptions.builder()
                                .model(properties.llm().generationModel())
                                .temperature(0.1))
                        .call()
                        .entity(DoubtBatch.class, spec -> spec.validateSchema()));

        return limitQuestions(tagPriorVersion(normalize(generated, request.module())), count);
    }

    /**
     * 加载向量检索上下文：混合搜索 → BGE 重排 → LLM 重排，并组装正文与检索片段。
     */
    private RetrievalContext loadRetrievalContext(ReviewRequest request) {
        String queryHint = "评审 " + request.version() + " " + java.util.Objects.toString(request.module(), "");
        String collection = resolveCollection(request.projectId(), queryHint);
        List<ChunkRecord> allChunks = observability.observe("qdrant.scroll", request.documentId(), request.version(),
                () -> store.scrollVersion(collection, request.documentId(), request.version()));
        if (allChunks.isEmpty()) {
            throw new DocumentNotFoundException(request.documentId(), request.version());
        }

        String query = "评审 " + request.version() + " 产品文档中未明确、歧义、冲突的规则。模块：" + Objects.toString(request.module(), "全部模块");
        List<ChunkRecord> hybrid = observability.observe("qdrant.hybrid_search", request.documentId(), request.version(),
                () -> store.hybridSearch(collection, query, request.documentId(), request.version()));
        List<ChunkRecord> bgeRanked = observability.observe("bge.rerank", request.documentId(), request.version(),
                () -> bgeReranker.rerank(query, hybrid, properties.retrieval().bgeTopK()));
        List<ChunkRecord> llmRanked = observability.observe("llm.rerank", request.documentId(), request.version(),
                () -> llmRerank(query, expandParents(bgeRanked)));

        List<ChunkRecord> versionChunks = filterByVersionPath(allChunks, request.version());
        if (versionChunks.isEmpty()) {
            versionChunks = allChunks;
        }
        String latestContext = joinParents(filterByModule(versionChunks, request.module()));
        String retrievedFromVersion = joinParents(filterByVersionPath(llmRanked, request.version()));
        String retrievedContext = retrievedFromVersion.isBlank() ? joinParents(llmRanked) : retrievedFromVersion;
        return new RetrievalContext(latestContext, retrievedContext);
    }

    private String resolveCollection(String projectId, String queryHint) {
        String resolved = projectId;
        if (resolved == null || resolved.isBlank()) {
            resolved = queryRouter.route(queryHint, null).projectId();
        }
        try {
            return projectRegistry.resolveRequirementCollection(resolved);
        } catch (IllegalArgumentException ignored) {
            return properties.qdrant().collection();
        }
    }

    /** 加载历史存疑文本供 LLM 去重；读取失败时返回占位说明。支持项目级 xlsx 路径。 */
    private String loadHistoricalContext(String projectId) {
        try {
            if (projectId != null && !projectId.isBlank()) {
                var project = projectRegistry.find(projectId).orElse(null);
                if (project != null && project.knowledge() != null
                        && project.knowledge().xlsxPath() != null && !project.knowledge().xlsxPath().isBlank()) {
                    var pk = project.knowledge();
                    return historicalDoubtService.formatForPrompt(
                            historicalDoubtService.loadPriorVersions(pk.xlsxPath(), pk.version(), pk.xlsxSheetPrefix()));
                }
            }
            return historicalDoubtService.formatForPrompt(historicalDoubtService.loadPriorVersions());
        }
        catch (IOException exception) {
            return "无历史存疑记录。";
        }
    }

    /** 为批次中每条存疑的模块名添加 [历史版本] 前缀。 */
    private DoubtBatch tagPriorVersion(DoubtBatch batch) {
        if (batch == null || batch.doubts() == null) {
            return new DoubtBatch(List.of());
        }
        List<RequirementDoubt> tagged = batch.doubts().stream()
                .map(doubt -> new RequirementDoubt(
                        prefixPriorModule(doubt.module()),
                        doubt.feature(),
                        doubt.question(),
                        doubt.type(),
                        doubt.status(),
                        doubt.sourceLocation()))
                .toList();
        return new DoubtBatch(tagged);
    }

    /** 确保模块名带 [历史版本] 前缀且避免重复添加。 */
    private String prefixPriorModule(String module) {
        if (module == null || module.isBlank()) {
            return "[历史版本]";
        }
        if (module.startsWith("[历史版本]")) {
            return module;
        }
        return "[历史版本] " + module;
    }

    /** 按模块关键词过滤分块；无匹配时返回原列表。 */
    private List<ChunkRecord> filterByModule(List<ChunkRecord> chunks, String module) {
        if (module == null || module.isBlank()) {
            return chunks;
        }
        String expected = module.toLowerCase(Locale.ROOT);
        List<ChunkRecord> matched = chunks.stream()
                .filter(chunk -> chunk.parentText().toLowerCase(Locale.ROOT).contains(expected))
                .toList();
        return matched.isEmpty() ? chunks : matched;
    }

    /** 按版本路径前缀过滤分块，确保只取指定版本目录下的文件。 */
    private List<ChunkRecord> filterByVersionPath(List<ChunkRecord> chunks, String version) {
        if (version == null || version.isBlank() || chunks == null || chunks.isEmpty()) {
            return chunks == null ? List.of() : chunks;
        }
        String prefix = version.trim().replace('\\', '/') + "/";
        return chunks.stream()
                .filter(chunk -> chunk.filename() != null && chunk.filename().replace('\\', '/').startsWith(prefix))
                .toList();
    }

    /** 去重、排序并可选覆盖模块名，规范化 LLM 输出批次。 */
    private DoubtBatch normalize(DoubtBatch batch, String requestedModule) {
        if (batch == null || batch.doubts() == null) {
            return new DoubtBatch(List.of());
        }
        Map<String, RequirementDoubt> unique = batch.doubts().stream()
                .filter(Objects::nonNull)
                .filter(doubt -> doubt.question() != null && !doubt.question().isBlank())
                .map(doubt -> requestedModule == null || requestedModule.isBlank() ? doubt : new RequirementDoubt(
                        requestedModule, doubt.feature(), doubt.question(), doubt.type(), doubt.status(), doubt.sourceLocation()))
                .collect(Collectors.toMap(
                        doubt -> normalizeQuestion(doubt.question()),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));

        List<RequirementDoubt> sorted = unique.values().stream()
                .sorted(Comparator.comparing(RequirementDoubt::module, Comparator.nullsLast(String::compareTo))
                        .thenComparing(RequirementDoubt::feature, Comparator.nullsLast(String::compareTo)))
                .toList();
        return new DoubtBatch(sorted);
    }

    /** 截断至配置的最大存疑条数。 */
    private DoubtBatch limitQuestions(DoubtBatch batch, int maxQuestions) {
        if (batch == null || batch.doubts() == null) {
            return new DoubtBatch(List.of());
        }
        if (batch.doubts().size() <= maxQuestions) {
            return batch;
        }
        return new DoubtBatch(batch.doubts().subList(0, maxQuestions));
    }

    /** 规范化问题文本用于去重比较（去标点、空白并转小写）。 */
    private String normalizeQuestion(String question) {
        return question.replaceAll("[\\s，。；：、？！?]", "").toLowerCase(Locale.ROOT);
    }

    /** 按 parentId 去重，每个父块只保留一条代表记录。 */
    private List<ChunkRecord> expandParents(List<ChunkRecord> chunks) {
        return chunks.stream().collect(Collectors.toMap(ChunkRecord::parentId, Function.identity(), (a, b) -> a,
                LinkedHashMap::new)).values().stream().toList();
    }

    /** 使用 LLM 对候选段落按评审相关性重排并截取 topK。 */
    private List<ChunkRecord> llmRerank(String query, List<ChunkRecord> candidates) {
        if (candidates.isEmpty()) return List.of();
        String passages = candidates.stream().map(chunk -> chunk.parentId() + "\n" + chunk.parentText())
                .collect(Collectors.joining("\n---\n"));
        RankedIds ranked = chatClient.prompt().system("""
                你是需求评审检索重排器。按对发现需求缺失、歧义、冲突的帮助程度排列候选段落。
                只能返回提供的 parentId，不得改写或创建ID。删除无关、重复和纯目录内容。
                """).user("检索目标：" + query + "\n候选段落：\n" + passages)
                .options(OpenAiChatOptions.builder()
                        .model(properties.llm().rerankerModel())
                        .temperature(0.0))
                .call().entity(RankedIds.class);
        Map<String, ChunkRecord> byId = candidates.stream().collect(Collectors.toMap(ChunkRecord::parentId, Function.identity()));
        if (ranked == null || ranked.parentIds() == null) return candidates.stream().limit(properties.retrieval().llmTopK()).toList();
        return ranked.parentIds().stream().map(byId::get).filter(Objects::nonNull).distinct()
                .limit(properties.retrieval().llmTopK()).toList();
    }

    /** 拼接父块文本为 LLM 上下文，按 parentOrder 排序并截断至最大字符数。 */
    private String joinParents(List<ChunkRecord> documents) {
        String context = documents.stream()
                .collect(Collectors.toMap(ChunkRecord::parentId, Function.identity(), (a, b) -> a, LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator.comparingInt(ChunkRecord::parentOrder))
                .map(document -> "[" + document.filename() + ", parent=" + document.parentId() + "]\n" + document.parentText())
                .distinct()
                .collect(Collectors.joining("\n\n"));
        return context.substring(0, Math.min(MAX_CONTEXT_CHARS, context.length()));
    }

    /** 检索上下文：最新正文与检索补充片段。 */
    private record RetrievalContext(String latestContext, String retrievedContext) {
    }

    /** LLM 重排返回的 parentId 有序列表。 */
    private record RankedIds(List<String> parentIds) {
    }
}
