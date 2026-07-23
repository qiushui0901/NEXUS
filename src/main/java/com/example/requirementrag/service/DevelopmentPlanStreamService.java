package com.example.requirementrag.service;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.DevelopmentPlanRequest;
import com.example.requirementrag.model.DevelopmentPlanStreamEvent;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
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

    public DevelopmentPlanStreamService(RagProperties properties, ProjectRegistry projectRegistry,
                                        QueryRouter queryRouter, QdrantHybridStore documentStore,
                                        CodeKnowledgeService codeKnowledgeService, ChatClient chatClient,
                                        ObjectMapper objectMapper, PlanSectionEvidenceMatcher evidenceMatcher) {
        this.properties = properties;
        this.projectRegistry = projectRegistry;
        this.queryRouter = queryRouter;
        this.documentStore = documentStore;
        this.codeKnowledgeService = codeKnowledgeService;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.evidenceMatcher = evidenceMatcher;
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
            String projectId = hasText(request.projectId())
                    ? request.projectId()
                    : queryRouter.route(request.query(), request.projectId()).projectId();

            List<ChunkRecord> documents = searchDocuments(projectId, request.query(), documentId, version).stream()
                    .limit(limit)
                    .toList();
            List<CodeChunk> code = searchCode(request.query(), projectId, limit);
            send(emitter, closed, event("retrieval", ++sequence,
                    Map.of("documentCount", documents.size(), "codeCount", code.size()), "相关材料检索完成"));

            long offset = sequence;
            Flux<String> content = chatClient.prompt()
                    .system(streamSystemPrompt())
                    .user(streamUserPrompt(request.query(), documents, code))
                    .options(GenerationChatOptions.forModel(properties.llm().generationModel()))
                    .stream()
                    .content();
            consumeModelStream(content, parsed -> sendUnchecked(emitter, closed,
                    resequence(enrichSectionEvent(parsed, code), offset)));

            long terminalSequence = offset + 10_000;
            send(emitter, closed, event("references", terminalSequence,
                    Map.of(
                            "documents", documents.stream().map(item -> Map.of(
                                    "filename", item.filename(),
                                    "excerpt", clip(item.parentText(), 260))).toList(),
                            "code", code),
                    "相关文件已整理"));
            send(emitter, closed, event("completed", terminalSequence + 1, Map.of(), "开发方案生成完成"));
            close(emitter, closed);
        }
        catch (Exception exception) {
            sendUnchecked(emitter, closed, event("error", Long.MAX_VALUE,
                    Map.of("message", safeMessage(exception)), "生成中断，可重新生成"));
            close(emitter, closed);
        }
    }

    /**
     * 消费模型流。提供方在已输出有效 NDJSON 后异常断流时，保留已生成内容并让上层正常收尾；
     * 若一个有效段落都没有产生，则继续抛出异常。
     */
    long consumeModelStream(Flux<String> content, Consumer<DevelopmentPlanStreamEvent> consumer) {
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
        if (emitted.get() == 0 && streamFailure != null) {
            throw streamFailure;
        }
        return emitted.get();
    }

    DevelopmentPlanStreamEvent enrichSectionEvent(DevelopmentPlanStreamEvent event, List<CodeChunk> code) {
        if (event == null || !"section".equals(event.type())) {
            return event;
        }
        JsonNode payload = evidenceMatcher.enrich(event.payload(), code);
        return new DevelopmentPlanStreamEvent(event.type(), event.sequence(), payload, event.message());
    }

    private List<ChunkRecord> searchDocuments(String query, String documentId, String version) {
        return searchDocuments(null, query, documentId, version);
    }

    private List<ChunkRecord> searchDocuments(String projectId, String query, String documentId, String version) {
        try {
            String collection = resolveRequirementCollection(projectId);
            return documentStore.hybridSearch(collection, query, documentId, version);
        }
        catch (RuntimeException exception) {
            return List.of();
        }
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

    private List<CodeChunk> searchCode(String query, String projectId, int limit) {
        try {
            return codeKnowledgeService.search(query, projectId, limit);
        }
        catch (RuntimeException exception) {
            return List.of();
        }
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

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
