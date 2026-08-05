package com.example.requirementrag.service;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.evidence.CitedText;
import com.example.requirementrag.evidence.EvidenceCitationService;
import com.example.requirementrag.evidence.EvidenceRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.DevelopmentPlanRequest;
import com.example.requirementrag.model.DevelopmentPlanStreamEvent;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.RagStageDiagnostic;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.observability.RagObservability;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalPipeline;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final Set<String> ALLOWED_PLAN_EVENT_TYPES = Set.of(
            "summary", "product-understanding", "constraint", "chain", "section",
            "implementation-step", "risk");

    private final RagProperties properties;
    private final RetrievalPipeline retrievalPipeline;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final PlanSectionEvidenceMatcher evidenceMatcher;
    private final RagObservability observability;
    private final EvidenceCitationService citationService;

    @Autowired
    public DevelopmentPlanStreamService(RagProperties properties, RetrievalPipeline retrievalPipeline,
                                        ChatClient chatClient, ObjectMapper objectMapper,
                                        PlanSectionEvidenceMatcher evidenceMatcher,
                                        RagObservability observability,
                                        EvidenceCitationService citationService) {
        this.properties = properties;
        this.retrievalPipeline = retrievalPipeline;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.evidenceMatcher = evidenceMatcher;
        this.observability = observability;
        this.citationService = citationService;
    }

    /** 向后兼容构造器，供聚焦单元测试与嵌入式调用方使用。 */
    public DevelopmentPlanStreamService(RagProperties properties, RetrievalPipeline retrievalPipeline,
                                        ChatClient chatClient, ObjectMapper objectMapper,
                                        PlanSectionEvidenceMatcher evidenceMatcher,
                                        RagObservability observability) {
        this(properties, retrievalPipeline, chatClient, objectMapper, evidenceMatcher, observability,
                new EvidenceCitationService());
    }

    /** 向后兼容构造器，供聚焦单元测试与嵌入式调用方使用。 */
    public DevelopmentPlanStreamService(RagProperties properties, ProjectRegistry projectRegistry,
                                        QueryRouter queryRouter, QdrantHybridStore documentStore,
                                        CodeKnowledgeService codeKnowledgeService, ChatClient chatClient,
                                        ObjectMapper objectMapper, PlanSectionEvidenceMatcher evidenceMatcher,
                                        RagObservability observability) {
        this(properties, new RetrievalPipeline(properties, projectRegistry, queryRouter, documentStore,
                codeKnowledgeService, observability), chatClient, objectMapper, evidenceMatcher, observability,
                new EvidenceCitationService());
    }

    /**
     * 开启一个开发方案 SSE 流：检索完成后逐行转发模型的 NDJSON 事件，
     * 直至收到 completed 事件或发生异常。生成在虚拟线程中异步进行。
     *
     * @param request 包含查询、项目、文档与版本信息的请求
     * @return 已启动的 SSE 发射器，事件通过其推送
     */
    public SseEmitter stream(DevelopmentPlanRequest request) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean();
        emitter.onTimeout(() -> close(emitter, closed));
        emitter.onCompletion(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));
        Thread.startVirtualThread(() -> generate(request, emitter, closed));
        return emitter;
    }

    /** 完整生成流程：检索 → 校验并转发模型事件 → 补充引用与警告 → 结束事件或错误事件。 */
    private void generate(DevelopmentPlanRequest request, SseEmitter emitter, AtomicBoolean closed) {
        long sequence = 0;
        try {
            send(emitter, closed, event("started", ++sequence, Map.of(), "正在准备开发方案"));
            RagOutcome<RetrievalBundle> retrieval;
            try {
                retrieval = retrievalPipeline.execute(new RetrievalRequest(request.query(),
                        RetrievalProfile.DEVELOPMENT_PLAN, request.projectId(), request.documentId(),
                        request.version(), request.limit()));
            } catch (RagUnavailableException unavailable) {
                for (RagWarning warning : unavailable.warnings()) {
                    sendUnchecked(emitter, closed, warningEvent(++sequence, warning));
                }
                throw unavailable;
            }
            RetrievalBundle bundle = retrieval.data();
            String documentId = bundle.documentId();
            String version = bundle.version();
            List<RagWarning> warnings = new ArrayList<>(retrieval.warnings());
            List<RagStageDiagnostic> diagnostics = new ArrayList<>(retrieval.stageDiagnostics());
            List<ChunkRecord> documents = bundle.requirementEvidence();
            List<CodeChunk> code = bundle.codeEvidence();
            EvidenceRegistry registry = EvidenceRegistry.from(bundle);
            EvidenceCitationService.Session citationSession = citationService.open(registry);
            Set<String> emittedWarnings = new LinkedHashSet<>();
            for (RagWarning warning : warnings) {
                send(emitter, closed, warningEvent(++sequence, warning));
                emittedWarnings.add(warningKey(warning));
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
                        .user(streamUserPrompt(request.query(), documents, code, registry))
                        .options(GenerationChatOptions.forModel(properties.llm().generationModel()))
                        .stream()
                        .content();
                generationOutcome = consumeValidatedModelStreamOutcome(content, parsed -> {
                    DevelopmentPlanStreamEvent validated = validateCitationEvent(parsed, citationSession, warnings);
                    if (validated == null) {
                        return false;
                    }
                    sendUnchecked(emitter, closed, resequence(enrichSectionEvent(validated, code), offset));
                    return true;
                }, generationStarted);
                recordOutcome(generationOutcome, documentId, version);
                diagnostics.addAll(generationOutcome.stageDiagnostics());
            }
            catch (RuntimeException exception) {
                appendWarnings(warnings, citationSession.warnings());
                for (RagWarning warning : warnings) {
                    if (emittedWarnings.add(warningKey(warning))) {
                        sendUnchecked(emitter, closed, warningEvent(offset + 9_000 + emittedWarnings.size(), warning));
                    }
                }
                long durationMs = elapsedMillis(generationStarted);
                observability.outcome("llm.generate.stream", documentId, version, RagOutcomeStatus.FAILED,
                        durationMs, "STREAM_GENERATION_FAILED", exception);
                throw exception;
            }
            appendWarnings(warnings, generationOutcome.warnings());
            appendWarnings(warnings, citationSession.warnings());
            status = overallStatus(documents, code, warnings);
            for (RagWarning warning : warnings) {
                if (emittedWarnings.add(warningKey(warning))) {
                    send(emitter, closed, warningEvent(offset + 9_000 + emittedWarnings.size(), warning));
                }
            }

            long terminalSequence = offset + 10_000;
            Map<String, Object> referencePayload = new java.util.LinkedHashMap<>();
            referencePayload.put("documents", documents.stream().map(item -> Map.of(
                    "filename", item.filename() == null ? "" : item.filename(),
                    "excerpt", clip(item.parentText(), 260))).toList());
            referencePayload.put("code", code);
            referencePayload.put("evidence", registry.references());
            send(emitter, closed, event("references", terminalSequence, referencePayload, "相关文件已整理"));
            send(emitter, closed, event("completed", terminalSequence + 1,
                    Map.of("status", status, "warnings", warnings, "stageDiagnostics", diagnostics,
                            "citationQuality", citationSession.quality()),
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
    /**
     * 消费模型流：将每个解析出的事件交给消费者。
     *
     * @param content  模型增量文本流
     * @param consumer 每个有效事件的处理回调
     * @return 成功转发的事件数量
     */
    long consumeModelStream(Flux<String> content, Consumer<DevelopmentPlanStreamEvent> consumer) {
        return consumeModelStreamOutcome(content, consumer, System.nanoTime()).data();
    }

    /** 同 {@link #consumeModelStream}，额外返回包含耗时与状态的 RAG 结果。 */
    RagOutcome<Long> consumeModelStreamOutcome(Flux<String> content,
                                                Consumer<DevelopmentPlanStreamEvent> consumer,
                                                long started) {
        return consumeAcceptedModelStreamOutcome(content, event -> {
            consumer.accept(event);
            return true;
        }, started);
    }

    /** 消费模型流并允许消费者通过返回 false 拒绝单个事件；拒绝的事件不计入成功数。 */
    RagOutcome<Long> consumeValidatedModelStreamOutcome(Flux<String> content,
                                                         Predicate<DevelopmentPlanStreamEvent> consumer,
                                                         long started) {
        return consumeAcceptedModelStreamOutcome(content, consumer, started);
    }

    /**
     * 消费模型流并统计被接受的事件数；模型流异常中断但已有有效事件时降级返回，
     * 一个有效事件都没有时抛出异常。
     */
    private RagOutcome<Long> consumeAcceptedModelStreamOutcome(Flux<String> content,
                                                                Predicate<DevelopmentPlanStreamEvent> consumer,
                                                                long started) {
        DevelopmentPlanStreamParser parser = new DevelopmentPlanStreamParser(objectMapper);
        AtomicLong emitted = new AtomicLong();
        RuntimeException streamFailure = null;
        try {
            content.doOnNext(chunk -> parser.accept(chunk).forEach(event -> {
                if (consumer.test(event)) {
                    emitted.incrementAndGet();
                }
            })).blockLast();
        }
        catch (RuntimeException exception) {
            streamFailure = exception;
        }
        for (DevelopmentPlanStreamEvent event : parser.finish()) {
            if (consumer.test(event)) {
                emitted.incrementAndGet();
            }
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

    /** 校验事件类型与证据引用：未支持的类型记警告并返回 null，其余事件回写校验后的证据 ID 与支持状态。 */
    DevelopmentPlanStreamEvent validateCitationEvent(DevelopmentPlanStreamEvent event,
                                                       EvidenceCitationService.Session citationSession,
                                                       List<RagWarning> warnings) {
        if (event == null || !ALLOWED_PLAN_EVENT_TYPES.contains(event.type())) {
            appendWarning(warnings, new RagWarning("llm.generate.stream", "UNKNOWN_PLAN_EVENT_TYPE",
                    "模型返回了未支持的方案事件，已忽略", 0));
            return null;
        }
        ObjectNode payload = event.payload() != null && event.payload().isObject()
                ? ((ObjectNode) event.payload()).deepCopy()
                : objectMapper.createObjectNode();
        List<String> requested = payload.path("evidenceIds").isArray()
                ? payload.path("evidenceIds").valueStream().map(JsonNode::asText).toList()
                : List.of();
        String text = "section".equals(event.type())
                ? payload.path("title").asText("")
                : payload.path("text").asText("");
        CitedText citation = citationSession.cite(text, requested);
        var evidenceIds = payload.putArray("evidenceIds");
        citation.evidenceIds().forEach(evidenceIds::add);
        payload.put("supportStatus", citation.supportStatus().name());
        return new DevelopmentPlanStreamEvent(event.type(), event.sequence(), payload, event.message());
    }

    /** 为 section 事件绑定本次检索命中的真实代码作为检查目标；非 section 事件原样返回。 */
    DevelopmentPlanStreamEvent enrichSectionEvent(DevelopmentPlanStreamEvent event, List<CodeChunk> code) {
        if (event == null || !"section".equals(event.type())) {
            return event;
        }
        JsonNode payload = evidenceMatcher.enrich(event.payload(), code);
        return new DevelopmentPlanStreamEvent(event.type(), event.sequence(), payload, event.message());
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

    private void appendWarnings(List<RagWarning> target, List<RagWarning> additions) {
        for (RagWarning warning : additions) appendWarning(target, warning);
    }

    private void appendWarning(List<RagWarning> target, RagWarning warning) {
        String key = warningKey(warning);
        if (target.stream().noneMatch(existing -> warningKey(existing).equals(key))) {
            target.add(warning);
        }
    }

    private String warningKey(RagWarning warning) {
        return warning.code() + "|" + warning.message();
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
                summary 的 payload 为 {"text":"...","evidenceIds":["上下文中的证据 ID"]}。
                product-understanding、constraint、chain、implementation-step、risk 使用相同的 text + evidenceIds 格式，每条单独一行。
                section 的 payload 为 {"title":"...","purpose":"...","relatedRules":[...],"keyQuestions":[...],"changeSuggestions":[...],"evidenceIds":["上下文中的证据 ID"],"plannedNodes":[{"id":"稳定的英文短标识","label":"建议新增的模块或接口","type":"api|service|config|state|test","description":"规划职责"}]}。
                evidenceIds 只能从上下文的 [evidenceId=...] 中选择；没有直接证据时返回空数组，禁止编造或用无关证据凑数。
                plannedNodes 只描述建议新增内容；禁止把代码命中里的文件路径、真实代码 ID 或现有类冒充规划节点，真实代码关联由系统补充。
                先理解产品规则，再映射到代码链路；不要说参考了文档；不要生成产品存疑；中文短句；逐行立即输出。
                """;
    }

    private String streamUserPrompt(String query, List<ChunkRecord> documents, List<CodeChunk> code,
                                    EvidenceRegistry registry) {
        return """
                用户问题：
                %s

                产品需求片段：
                %s

                代码命中片段：
                %s

                按顺序输出：1 条 summary，5-8 条 product-understanding，5-8 条 constraint，完整 chain，约 7 个 section，实施顺序和风险。每条都返回 evidenceIds。现在开始逐行输出。
                """.formatted(query,
                registry.promptRequirementContext(documents, MAX_DOCUMENT_CONTEXT_CHARS),
                registry.promptCodeContext(code, MAX_CODE_CONTEXT_CHARS));
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
