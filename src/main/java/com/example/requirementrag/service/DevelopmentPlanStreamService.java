package com.example.requirementrag.service;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.DevelopmentPlanRequest;
import com.example.requirementrag.model.DevelopmentPlanStreamEvent;
import com.example.requirementrag.model.QueryRouting;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.RagStageDiagnostic;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.observability.RagObservability;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/** 真实流式生成开发方案，并把模型 NDJSON 转换为 SSE。 */
@Service
public class DevelopmentPlanStreamService {

    private static final long STREAM_TIMEOUT_MS = 240_000L;
    private static final int MAX_DOCUMENT_CONTEXT_CHARS = 12_000;
    private static final int MAX_CODE_CONTEXT_CHARS = 8_000;

    private final RagProperties properties;
    private final ProjectRegistry projectRegistry;
    private final QueryRouter queryRouter;
    private final QdrantHybridStore documentStore;
    private final CodeKnowledgeService codeKnowledgeService;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final PlanSectionEvidenceMatcher evidenceMatcher;
    private final RagObservability observability;

    public DevelopmentPlanStreamService(RagProperties properties, ProjectRegistry projectRegistry,
                                        QueryRouter queryRouter, QdrantHybridStore documentStore,
                                        CodeKnowledgeService codeKnowledgeService, ChatClient chatClient,
                                        ObjectMapper objectMapper, PlanSectionEvidenceMatcher evidenceMatcher,
                                        RagObservability observability) {
        this.properties = properties;
        this.projectRegistry = projectRegistry;
        this.queryRouter = queryRouter;
        this.documentStore = documentStore;
        this.codeKnowledgeService = codeKnowledgeService;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.evidenceMatcher = evidenceMatcher;
        this.observability = observability;
    }

    public SseEmitter stream(DevelopmentPlanRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean();
        emitter.onTimeout(() -> close(emitter, closed));
        emitter.onCompletion(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));
        Thread.startVirtualThread(() -> generate(request, emitter, closed));
        return emitter;
    }

    private void generate(DevelopmentPlanRequest request, SseEmitter emitter, AtomicBoolean closed) {
        long sequence = 0;
        try {
            send(emitter, closed, event("started", ++sequence, Map.of(), "正在准备开发方案"));
            String documentId = hasText(request.documentId()) ? request.documentId() : properties.knowledge().documentId();
            String version = hasText(request.version()) ? request.version() : properties.knowledge().version();
            int limit = Math.min(Math.max(request.limit() == null ? 8 : request.limit(), 3), 20);
            List<RagWarning> warnings = new ArrayList<>();
            List<RagStageDiagnostic> diagnostics = new ArrayList<>();
            RagOutcome<QueryRouting> routing = queryRouter.routeWithOutcome(request.query(), request.projectId());
            recordOutcome(routing, documentId, version);
            collect(routing, warnings, diagnostics);
            String projectId = routing.data().projectId();

            RagOutcome<List<ChunkRecord>> documentOutcome = searchDocuments(projectId, request.query(), documentId, version);
            RagOutcome<List<CodeChunk>> codeOutcome = searchCode(request.query(), projectId, limit, documentId, version);
            collect(documentOutcome, warnings, diagnostics);
            collect(codeOutcome, warnings, diagnostics);
            List<ChunkRecord> documents = documentOutcome.data().stream().limit(limit).toList();
            List<CodeChunk> code = codeOutcome.data();
            if (documents.isEmpty() && code.isEmpty()
                    && (documentOutcome.status() == RagOutcomeStatus.FAILED
                    || codeOutcome.status() == RagOutcomeStatus.FAILED)) {
                for (RagWarning warning : warnings) {
                    sendUnchecked(emitter, closed, warningEvent(++sequence, warning));
                }
                throw new RagUnavailableException(warnings);
            }
            for (RagWarning warning : warnings) {
                send(emitter, closed, warningEvent(++sequence, warning));
            }
            RagOutcomeStatus status = overallStatus(documents, code, warnings);
            send(emitter, closed, event("retrieval", ++sequence,
                    Map.of("documentCount", documents.size(), "codeCount", code.size(),
                            "status", status, "stageDiagnostics", diagnostics), "相关材料检索完成"));

            long offset = sequence;
            long generationStarted = System.nanoTime();
            RagOutcome<Long> generationOutcome;
            try {
                Flux<String> content = chatClient.prompt()
                        .system(streamSystemPrompt())
                        .user(streamUserPrompt(request.query(), documents, code))
                        .options(GenerationChatOptions.forModel(properties.llm().generationModel()))
                        .stream()
                        .content();
                generationOutcome = consumeModelStreamOutcome(content, parsed -> sendUnchecked(emitter, closed,
                        resequence(enrichSectionEvent(parsed, code), offset)), generationStarted);
                recordOutcome(generationOutcome, documentId, version);
                diagnostics.addAll(generationOutcome.stageDiagnostics());
            }
            catch (RuntimeException exception) {
                long durationMs = elapsedMillis(generationStarted);
                observability.outcome("llm.generate.stream", documentId, version, RagOutcomeStatus.FAILED,
                        durationMs, "STREAM_GENERATION_FAILED", exception);
                throw exception;
            }
            if (!generationOutcome.warnings().isEmpty()) {
                warnings.addAll(generationOutcome.warnings());
                status = RagOutcomeStatus.DEGRADED;
                for (RagWarning warning : generationOutcome.warnings()) {
                    send(emitter, closed, warningEvent(offset + 9_000, warning));
                }
            }

            long terminalSequence = offset + 10_000;
            send(emitter, closed, event("references", terminalSequence,
                    Map.of(
                            "documents", documents.stream().map(item -> Map.of(
                                    "filename", item.filename(),
                                    "excerpt", clip(item.parentText(), 260))).toList(),
                            "code", code),
                    "相关文件已整理"));
            send(emitter, closed, event("completed", terminalSequence + 1,
                    Map.of("status", status, "warnings", warnings, "stageDiagnostics", diagnostics),
                    "开发方案生成完成"));
            close(emitter, closed);
        }
        catch (Exception exception) {
            sendUnchecked(emitter, closed, event("error", Long.MAX_VALUE,
                    Map.of("message", publicErrorMessage(exception), "status", RagOutcomeStatus.FAILED),
                    "生成中断，可重新生成"));
            close(emitter, closed);
        }
    }

    /**
     * 消费模型流。提供方在已输出有效 NDJSON 后异常断流时，保留已生成内容并让上层正常收尾；
     * 若一个有效段落都没有产生，则继续抛出异常。
     */
    long consumeModelStream(Flux<String> content, Consumer<DevelopmentPlanStreamEvent> consumer) {
        return consumeModelStreamOutcome(content, consumer, System.nanoTime()).data();
    }

    RagOutcome<Long> consumeModelStreamOutcome(Flux<String> content,
                                                Consumer<DevelopmentPlanStreamEvent> consumer,
                                                long started) {
        DevelopmentPlanStreamParser parser = new DevelopmentPlanStreamParser(objectMapper);
        AtomicLong emitted = new AtomicLong();
        RuntimeException streamFailure = null;
        try {
            content.doOnNext(chunk -> parser.accept(chunk).forEach(event -> {
                consumer.accept(event);
                emitted.incrementAndGet();
            })).blockLast();
        }
        catch (RuntimeException exception) {
            streamFailure = exception;
        }
        for (DevelopmentPlanStreamEvent event : parser.finish()) {
            consumer.accept(event);
            emitted.incrementAndGet();
        }
        if (emitted.get() == 0) {
            if (streamFailure != null) {
                throw streamFailure;
            }
            throw new IllegalStateException("Model stream produced no valid events");
        }
        long durationMs = elapsedMillis(started);
        if (streamFailure != null) {
            return RagOutcome.degraded(emitted.get(), "llm.generate.stream", "STREAM_PARTIAL_RESULT",
                    "模型流提前结束，已保留有效内容", durationMs, emitted.get());
        }
        return RagOutcome.of(RagOutcomeStatus.SUCCESS, emitted.get(), "llm.generate.stream",
                durationMs, emitted.get());
    }

    DevelopmentPlanStreamEvent enrichSectionEvent(DevelopmentPlanStreamEvent event, List<CodeChunk> code) {
        if (event == null || !"section".equals(event.type())) {
            return event;
        }
        JsonNode payload = evidenceMatcher.enrich(event.payload(), code);
        return new DevelopmentPlanStreamEvent(event.type(), event.sequence(), payload, event.message());
    }

    private RagOutcome<List<ChunkRecord>> searchDocuments(String projectId, String query,
                                                           String documentId, String version) {
        return retrieve("qdrant.hybrid_search", documentId, version, "DOCUMENT_RETRIEVAL_UNAVAILABLE",
                "需求文档检索暂时不可用",
                () -> documentStore.hybridSearch(resolveRequirementCollection(projectId), query, documentId, version));
    }

    private String resolveRequirementCollection(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return properties.qdrant().collection();
        }
        try {
            return projectRegistry.resolveRequirementCollection(projectId);
        } catch (IllegalArgumentException ignored) {
            return properties.qdrant().collection();
        }
    }

    private RagOutcome<List<CodeChunk>> searchCode(String query, String projectId, int limit,
                                                    String documentId, String version) {
        return retrieve("code.hybrid_search", documentId, version, "CODE_RETRIEVAL_UNAVAILABLE",
                "代码检索暂时不可用", () -> codeKnowledgeService.search(query, projectId, limit));
    }

    private <T> RagOutcome<List<T>> retrieve(String stage, String documentId, String version,
                                              String warningCode, String warningMessage,
                                              Supplier<List<T>> action) {
        long started = System.nanoTime();
        try {
            List<T> data = action.get();
            List<T> safeData = data == null ? List.of() : data;
            RagOutcomeStatus status = safeData.isEmpty() ? RagOutcomeStatus.NO_RESULTS : RagOutcomeStatus.SUCCESS;
            RagOutcome<List<T>> outcome = RagOutcome.of(status, safeData, stage,
                    elapsedMillis(started), safeData.size());
            recordOutcome(outcome, documentId, version);
            return outcome;
        }
        catch (RuntimeException exception) {
            long durationMs = elapsedMillis(started);
            RagOutcome<List<T>> outcome = RagOutcome.failed(List.of(), stage, warningCode, warningMessage, durationMs);
            observability.outcome(stage, documentId, version, RagOutcomeStatus.FAILED,
                    durationMs, warningCode, exception);
            return outcome;
        }
    }

    private void collect(RagOutcome<?> outcome, List<RagWarning> warnings, List<RagStageDiagnostic> diagnostics) {
        warnings.addAll(outcome.warnings());
        diagnostics.addAll(outcome.stageDiagnostics());
    }

    private void recordOutcome(RagOutcome<?> outcome, String documentId, String version) {
        for (RagStageDiagnostic diagnostic : outcome.stageDiagnostics()) {
            String warningCode = outcome.warnings().isEmpty() ? null : outcome.warnings().getFirst().code();
            observability.outcome(diagnostic.stage(), documentId, version, diagnostic.status(),
                    diagnostic.durationMs(), warningCode, null);
        }
    }

    private RagOutcomeStatus overallStatus(List<ChunkRecord> documents, List<CodeChunk> code,
                                           List<RagWarning> warnings) {
        if (!warnings.isEmpty()) {
            return RagOutcomeStatus.DEGRADED;
        }
        return documents.isEmpty() && code.isEmpty() ? RagOutcomeStatus.NO_RESULTS : RagOutcomeStatus.SUCCESS;
    }

    private DevelopmentPlanStreamEvent warningEvent(long sequence, RagWarning warning) {
        return event("warning", sequence, warning, warning.message());
    }

    private String streamSystemPrompt() {
        return """
                你是一名资深游戏后端主程。基于产品需求片段和当前项目代码命中，实时生成可落地的开发方案。
                只能输出 NDJSON：每行必须是一个完整 JSON 对象，不能输出 Markdown、代码围栏或额外说明。
                JSON 格式：{"type":"事件类型","message":"当前阶段","payload":{...}}
                事件类型只允许 summary、product-understanding、constraint、chain、section、implementation-step、risk。
                summary 的 payload 为 {"text":"..."}。
                product-understanding、constraint、chain、implementation-step、risk 的 payload 为 {"text":"..."}，每条单独一行。
                section 的 payload 为 {"title":"...","purpose":"...","relatedRules":["该环节服务的具体产品规则"],"keyQuestions":[...],"changeSuggestions":[...],"plannedNodes":[{"id":"稳定的英文短标识","label":"建议新增的模块或接口","type":"api|service|config|state|test","description":"规划职责"}]}，每个环节单独一行。
                plannedNodes 只描述建议新增内容；禁止把代码命中里的文件路径、真实代码 ID 或现有类冒充规划节点，真实代码关联由系统补充。
                先理解产品规则，再映射到代码链路；不要说参考了文档；不要生成产品存疑；中文短句；逐行立即输出。
                """;
    }

    private String streamUserPrompt(String query, List<ChunkRecord> documents, List<CodeChunk> code) {
        return """
                用户问题：
                %s

                产品需求片段：
                %s

                代码命中片段：
                %s

                按顺序输出：1 条 summary，5-8 条 product-understanding，5-8 条 constraint，完整 chain，约 7 个 section，实施顺序和风险。现在开始逐行输出。
                """.formatted(query, documentContext(documents), codeContext(code));
    }

    private String documentContext(List<ChunkRecord> documents) {
        StringBuilder text = new StringBuilder();
        for (ChunkRecord document : documents) {
            text.append("文件：").append(document.filename()).append('\n')
                    .append(document.parentText()).append("\n\n");
            if (text.length() >= MAX_DOCUMENT_CONTEXT_CHARS) break;
        }
        return clip(text.toString(), MAX_DOCUMENT_CONTEXT_CHARS);
    }

    private String codeContext(List<CodeChunk> code) {
        StringBuilder text = new StringBuilder();
        for (CodeChunk chunk : code) {
            text.append(chunk.symbolType()).append(' ').append(chunk.symbolName())
                    .append(" @ ").append(chunk.filePath()).append(':').append(chunk.startLine()).append('\n')
                    .append(chunk.text()).append("\n\n");
            if (text.length() >= MAX_CODE_CONTEXT_CHARS) break;
        }
        return clip(text.toString(), MAX_CODE_CONTEXT_CHARS);
    }

    private DevelopmentPlanStreamEvent event(String type, long sequence, Object payload, String message) {
        JsonNode node = objectMapper.valueToTree(payload);
        return new DevelopmentPlanStreamEvent(type, sequence, node, message);
    }

    private DevelopmentPlanStreamEvent resequence(DevelopmentPlanStreamEvent event, long offset) {
        return new DevelopmentPlanStreamEvent(event.type(), offset + event.sequence(), event.payload(), event.message());
    }

    private void send(SseEmitter emitter, AtomicBoolean closed, DevelopmentPlanStreamEvent event) throws IOException {
        if (closed.get()) return;
        emitter.send(SseEmitter.event().name(event.type()).data(event, MediaType.APPLICATION_JSON));
    }

    private void sendUnchecked(SseEmitter emitter, AtomicBoolean closed, DevelopmentPlanStreamEvent event) {
        try {
            send(emitter, closed, event);
        }
        catch (IOException exception) {
            close(emitter, closed);
        }
    }

    private void close(SseEmitter emitter, AtomicBoolean closed) {
        if (closed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String clip(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }

    private long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private String publicErrorMessage(Exception exception) {
        return exception instanceof RagUnavailableException
                ? exception.getMessage()
                : "RAG 处理失败，请稍后重试";
    }
}
