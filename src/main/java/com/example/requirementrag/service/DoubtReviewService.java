package com.example.requirementrag.service;

import com.example.requirementrag.model.DoubtBatch;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.RequirementDoubt;
import com.example.requirementrag.model.ReviewRequest;
import org.springframework.ai.chat.client.ChatClient;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalPipeline;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import com.example.requirementrag.config.RagProperties;
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
 * 基于统一检索管线证据的存疑评审服务。
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
            你是一名资深需求评审人员。请仅针对版本 %s 的产品文档，输出恰好 %d 条待产品确认的存疑。

            规则：
            - 问题必须来自版本 %s 的产品文档正文。
            - 对照历史存疑去重：已在旧版本问过且已有产品解答的，不再重复追问。
            - 当前版本文档可能不完整，很多规则此前已确认；若历史记录已覆盖同类问题，跳过即可.
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

            当前版本产品文档正文：
            ---
            %s
            ---

            当前版本检索补充片段：
            ---
            %s
            ---

            请输出恰好 %d 条当前版本新问题。
            """;

    private static final String PRIOR_SYSTEM_PROMPT = """
            你是一名资深需求评审人员。请从历史版本存疑中，输出恰好 %d 条「以前版本存疑」。

            规则：
            - 优先选择历史记录中尚无产品解答、或解答仍不充分的问题。
            - 可与当前版本仍相关，但问题本身属于旧版本遗留，不是当前版本文档中新发现的问题。
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
    private final RetrievalPipeline retrievalPipeline;
    private final RagProperties properties;
    private final ProjectRegistry projectRegistry;
    private final RagObservability observability;
    private final HistoricalDoubtService historicalDoubtService;

    public DoubtReviewService(ChatClient chatClient, RetrievalPipeline retrievalPipeline,
                              RagProperties properties, ProjectRegistry projectRegistry,
                              RagObservability observability, HistoricalDoubtService historicalDoubtService) {
        this.chatClient = chatClient;
        this.retrievalPipeline = retrievalPipeline;
        this.properties = properties;
        this.projectRegistry = projectRegistry;
        this.observability = observability;
        this.historicalDoubtService = historicalDoubtService;
    }

    /**
     * 通过共享检索管线加载当前版本文档上下文并生成新存疑。
     *
     * @param request 含文档、版本与限定模块的评审请求
     * @return 规范化并按条数截断后的当前版本存疑批次
     */
    public DoubtBatch reviewCurrentVersion(ReviewRequest request) {
        RetrievalContext context = loadRetrievalContext(request);
        String historicalContext = loadHistoricalContext(request.projectId());
        int count = properties.review().currentVersionQuestions();

        DoubtBatch generated = observability.observe("llm.generate.current", request.documentId(), request.version(),
                () -> chatClient.prompt()
                        .system(CURRENT_SYSTEM_PROMPT.formatted(request.version(), count, request.version(), QUESTION_STYLE))
                        .user(CURRENT_USER_PROMPT.formatted(
                                request.documentId(),
                                request.version(),
                                Objects.toString(request.module(), "全部"),
                                historicalContext,
                                context.latestContext(),
                                context.retrievedContext(),
                                count))
                        .options(GenerationChatOptions.forModel(properties.llm().resolvedDoubtReviewModel()))
                        .call()
                        .entity(DoubtBatch.class));

        return limitQuestions(normalize(generated, request.module()), count);
    }

    /**
     * 从历史存疑中生成以前版本遗留问题。
     *
     * @param request 含文档、版本与限定模块的评审请求
     * @return 模块名带 [历史版本] 前缀、按条数截断后的历史存疑批次
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
                        .options(GenerationChatOptions.forModel(properties.llm().resolvedDoubtReviewModel()))
                        .call()
                        .entity(DoubtBatch.class));

        return limitQuestions(tagPriorVersion(normalize(generated, request.module())), count);
    }

    /**
     * 加载统一管线已经完成召回与重排的版本正文和检索片段。
     */
    private RetrievalContext loadRetrievalContext(ReviewRequest request) {
        String query = "评审版本 " + request.version() + " 产品文档中未明确、歧义、冲突的规则。模块："
                + Objects.toString(request.module(), "全部模块");
        RagOutcome<RetrievalBundle> outcome = retrievalPipeline.execute(new RetrievalRequest(
                query, RetrievalProfile.REQUIREMENT_REVIEW, request.projectId(), request.documentId(),
                request.version(), properties.retrieval().bgeTopK(), true));
        RetrievalBundle bundle = outcome.data();
        List<ChunkRecord> allChunks = bundle.requirementCorpus();
        List<ChunkRecord> ranked = bundle.requirementEvidence();
        if (allChunks.isEmpty() && ranked.isEmpty()) {
            throw new DocumentNotFoundException(bundle.documentId(), bundle.version());
        }

        List<ChunkRecord> versionChunks = filterByVersionPath(allChunks, bundle.version());
        if (versionChunks.isEmpty()) {
            versionChunks = allChunks.isEmpty() ? ranked : allChunks;
        }
        String latestContext = joinParents(filterByModule(versionChunks, request.module()));
        String retrievedFromVersion = joinParents(filterByVersionPath(ranked, bundle.version()));
        String retrievedContext = retrievedFromVersion.isBlank() ? joinParents(ranked) : retrievedFromVersion;
        return new RetrievalContext(latestContext, retrievedContext);
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

}
