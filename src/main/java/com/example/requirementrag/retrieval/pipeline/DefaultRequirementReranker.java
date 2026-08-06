package com.example.requirementrag.retrieval.pipeline;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.RagStageDiagnostic;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.observability.RagObservability;
import com.example.requirementrag.rerank.BgeReranker;
import com.example.requirementrag.service.GenerationChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 执行配置的 BGE 重排，若启用则继续 LLM 重排的需求证据重排策略。 */
@Service
public class DefaultRequirementReranker implements RequirementReranker {
    private static final String BGE_STAGE = "bge.rerank";
    private static final String BGE_SINGLETON_SKIP_STAGE = "bge.rerank.singleton_skip";
    private static final String LLM_STAGE = "llm.rerank";

    private final BgeReranker bgeReranker;
    private final ChatClient chatClient;
    private final RagProperties properties;
    private final RagObservability observability;

    public DefaultRequirementReranker(BgeReranker bgeReranker, ChatClient chatClient,
                                      RagProperties properties, RagObservability observability) {
        this.bgeReranker = bgeReranker;
        this.chatClient = chatClient;
        this.properties = properties;
        this.observability = observability;
    }

    /**
     * 依次执行 BGE 重排与（可选的）LLM 重排，最后截取 limit 条。
     * 单候选且开启 child-first 时跳过 BGE；各阶段失败均降级回退为原始顺序并记录警告与诊断。
     *
     * @param query      检索查询文本
     * @param documentId 文档 ID（用于可观测性上报）
     * @param version    文档版本号（用于可观测性上报）
     * @param candidates 待重排的候选分块
     * @param limit      最终返回的最大分块数
     * @return 重排后的结果；有警告时为 DEGRADED，候选为空时为 NO_RESULTS
     */
    @Override
    public RagOutcome<List<ChunkRecord>> rerank(String query, String documentId, String version,
                                                List<ChunkRecord> candidates, int limit) {
        List<ChunkRecord> source = candidates == null ? List.of() : List.copyOf(candidates);
        if (source.isEmpty()) {
            return RagOutcome.of(RagOutcomeStatus.NO_RESULTS, List.of(), BGE_STAGE, 0, 0);
        }
        List<RagWarning> warnings = new ArrayList<>();
        List<RagStageDiagnostic> diagnostics = new ArrayList<>();
        RagProperties.Retrieval retrieval = properties.retrieval();
        boolean childFirstRerank = retrieval != null && retrieval.resolvedChildFirstRerankEnabled();
        List<ChunkRecord> bge;
        if (childFirstRerank && source.size() == 1) {
            bge = source;
            diagnostics.add(new RagStageDiagnostic(
                    BGE_SINGLETON_SKIP_STAGE, RagOutcomeStatus.SUCCESS, 0, source.size()));
            observability.outcome(BGE_SINGLETON_SKIP_STAGE, documentId, version,
                    RagOutcomeStatus.SUCCESS, 0, null, null);
        } else {
            int bgeTopK = Math.min(retrieval == null ? limit : retrieval.resolvedBgeTopK(), source.size());
            List<ChunkRecord> bgeInput = source.size() <= bgeTopK ? source : source.subList(0, bgeTopK);
            bge = stage(BGE_STAGE, "BGE_RERANK_UNAVAILABLE", "BGE 重排暂时不可用",
                    documentId, version, bgeInput,
                    () -> bgeReranker.rerank(query, bgeInput, bgeTopK),
                    warnings, diagnostics);
        }
        List<ChunkRecord> result = bge;
        if (retrieval != null && retrieval.llmRerankEnabled()) {
            result = stage(LLM_STAGE, "LLM_RERANK_UNAVAILABLE", "LLM 重排暂时不可用",
                    documentId, version, bge, () -> llmRerank(query, bge),
                    warnings, diagnostics);
        }
        result = result.stream().limit(limit).toList();
        return new RagOutcome<>(warnings.isEmpty() ? RagOutcomeStatus.SUCCESS : RagOutcomeStatus.DEGRADED,
                result, warnings, diagnostics);
    }

    /** 调用 LLM 对候选段落排序：返回的 ID 顺序即为结果；LLM 输出为空时保留候选原序。 */
    private List<ChunkRecord> llmRerank(String query, List<ChunkRecord> candidates) {
        Map<String, ChunkRecord> byId = candidates.stream().collect(Collectors.toMap(
                this::stableId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        String passages = byId.entrySet().stream()
                .map(entry -> entry.getKey() + "\n" + entry.getValue().parentText())
                .collect(Collectors.joining("\n---\n"));
        RankedIds ranked = chatClient.prompt().system("""
                        你是需求证据检索重排器。按与检索目标的相关性排列候选段落。
                        只能返回提供的 ID，不得改写或创建 ID。删除无关、重复和纯目录内容。
                        """)
                .user("检索目标：" + query + "\n候选段落：\n" + passages)
                .options(GenerationChatOptions.forModel(properties.llm().rerankerModel()).build())
                .call().entity(RankedIds.class);
        if (ranked == null || ranked.ids() == null || ranked.ids().isEmpty()) return candidates;
        List<ChunkRecord> ordered = ranked.ids().stream().map(byId::get).filter(Objects::nonNull).distinct().toList();
        return ordered.isEmpty() ? candidates : ordered;
    }

    /** 执行单个重排阶段：成功时记录 SUCCESS 诊断，异常时降级回退并记录警告与 DEGRADED 诊断。 */
    private List<ChunkRecord> stage(String stage, String warningCode, String warningMessage,
                                    String documentId, String version, List<ChunkRecord> fallback,
                                    java.util.function.Supplier<List<ChunkRecord>> action,
                                    List<RagWarning> warnings, List<RagStageDiagnostic> diagnostics) {
        long started = System.nanoTime();
        try {
            List<ChunkRecord> value = action.get();
            List<ChunkRecord> result = value == null || value.isEmpty() ? fallback : List.copyOf(value);
            long duration = elapsedMillis(started);
            diagnostics.add(new RagStageDiagnostic(stage, RagOutcomeStatus.SUCCESS, duration, result.size()));
            observability.outcome(stage, documentId, version, RagOutcomeStatus.SUCCESS, duration, null, null);
            return result;
        } catch (RuntimeException exception) {
            long duration = elapsedMillis(started);
            warnings.add(new RagWarning(stage, warningCode, warningMessage, duration));
            diagnostics.add(new RagStageDiagnostic(stage, RagOutcomeStatus.DEGRADED, duration, fallback.size()));
            observability.outcome(stage, documentId, version, RagOutcomeStatus.DEGRADED,
                    duration, warningCode, exception);
            return fallback;
        }
    }

    /** 生成 LLM 重排用的稳定 ID：优先 parentId，缺失时用 filename + parentOrder 拼接。 */
    private String stableId(ChunkRecord chunk) {
        return chunk.parentId() == null || chunk.parentId().isBlank()
                ? chunk.filename() + ':' + chunk.parentOrder() : chunk.parentId();
    }

    private long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private record RankedIds(List<String> ids) {
    }
}
