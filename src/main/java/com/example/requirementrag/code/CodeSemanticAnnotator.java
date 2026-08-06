package com.example.requirementrag.code;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.service.GenerationChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 使用 LLM 为代码 chunk 生成中英文业务描述、关键词、用户问题与同义词。
 * 分层标注：缓存命中直接复用；核心业务代码（Controller/Service/Manager/Dao 等）走 LLM；
 * 非核心代码（Model/DTO/Util/Enum 等）走静态分析标注（零成本）。
 * LLM 连续失败达阈值后熔断，剩余 chunk 全部降级为静态标注。
 */
@Component
public class CodeSemanticAnnotator {

    private static final Logger log = LoggerFactory.getLogger(CodeSemanticAnnotator.class);
    private static final int BATCH_SIZE = 25;
    private static final int MAX_CONSECUTIVE_LLM_FAILURES = 3;
    private static final int TEXT_PREVIEW_CHARS = 500;

    private static final String SYSTEM_PROMPT = """
            你是代码语义标注器。为每个代码片段生成完整的业务元数据。
            返回 JSON 数组，每个元素格式：
            {
              "businessDescCn": "中文业务描述（一句话说明该代码的业务含义）",
              "businessDescEn": "English business description",
              "keywords": ["中文关键词", "english keyword"],
              "userQuestions": ["用户可能问的自然语言问题1", "问题2"],
              "synonyms": ["代码术语的中文同义词1", "同义词2"]
            }

            关键要求：
            - userQuestions: 生成2-3个用户可能用自然语言提问来找到这段代码的问题（如"支付失败怎么处理"）
            - synonyms: 列出代码中专业术语的中文同义表达（如refund→退款/退钱/资金返还）
            - keywords: 包含中英文双语关键词
            - 数组长度必须和输入片段数量一致
            - 只返回 JSON，不要其他文字
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final RagProperties properties;

    public CodeSemanticAnnotator(ChatClient chatClient, ObjectMapper objectMapper, RagProperties properties) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 分层标注 + 缓存。
     * Layer 1: 缓存命中 → 直接复用
     * Layer 2: 核心业务代码 (Controller/Service/Manager/Dao) → LLM 标注
     * Layer 3: 非核心代码 (Model/DTO/Util/Enum) → 静态分析标注 (零成本)
     *
     * @param chunks 待标注的代码块（扫描阶段产物）
     * @param cache  标注缓存（键为源码哈希），可为 null 或空 Map
     * @return 标注完成后的代码块列表
     */
    public List<CodeChunk> annotateWithCache(List<CodeChunk> chunks,
                                              Map<String, CodeQdrantStore.AnnotationEntry> cache) {
        if (chunks == null || chunks.isEmpty()) return List.of();

        List<CodeChunk> coreChunks = new ArrayList<>();
        List<CodeChunk> nonCoreChunks = new ArrayList<>();
        List<CodeChunk> fromCache = new ArrayList<>();

        for (CodeChunk chunk : chunks) {
            String hash = CodeQdrantStore.sourceHash(chunk.text());
            CodeQdrantStore.AnnotationEntry cached = cache != null ? cache.get(hash) : null;
            if (cached != null && !cached.businessDescCn().isBlank()) {
                fromCache.add(chunk.withFullSemantics(
                        cached.businessDescCn(), cached.businessDescEn(),
                        chunk.callRelation(), cached.keywords(),
                        cached.userQuestions(), cached.synonyms()));
            } else if (isCoreBusiness(chunk)) {
                coreChunks.add(chunk);
            } else {
                nonCoreChunks.add(chunk);
            }
        }

        log.info("分层标注: 缓存命中 {}, 核心代码(LLM) {}, 非核心(静态) {}, 共 {}",
                fromCache.size(), coreChunks.size(), nonCoreChunks.size(), chunks.size());

        List<CodeChunk> llmAnnotated = annotate(coreChunks);
        List<CodeChunk> staticAnnotated = nonCoreChunks.stream()
                .map(this::staticAnnotate).toList();

        List<CodeChunk> result = new ArrayList<>(chunks.size());
        result.addAll(fromCache);
        result.addAll(llmAnnotated);
        result.addAll(staticAnnotated);
        return result;
    }

    private boolean isCoreBusiness(CodeChunk chunk) {
        String className = chunk.className() == null ? "" : chunk.className().toLowerCase();
        String filePath = chunk.filePath() == null ? "" : chunk.filePath().toLowerCase();
        return containsAny(className, "controller", "service", "manager", "dao",
                "handler", "moa", "facade", "processor", "rpc")
                || containsAny(filePath, "/controller/", "/service/", "/manager/",
                "/dao/", "/handler/", "/moa/", "/rpc/");
    }

    private CodeChunk staticAnnotate(CodeChunk chunk) {
        String className = safe(chunk.className());
        String symbolName = safe(chunk.symbolName());
        String type = safe(chunk.symbolType());

        String descCn;
        String descEn;
        if ("class".equals(type)) {
            String role = classRoleHint(className);
            descCn = className + role;
            descEn = className + " " + role.replace("类", "class").replace("对象", "object")
                    .replace("结构", "structure").replace("参数", "params");
        } else if ("method".equals(type)) {
            String prefix = className.isEmpty() ? "" : className + ".";
            descCn = prefix + symbolName + " 方法";
            descEn = prefix + symbolName + " method";
        } else {
            descCn = className.isEmpty() ? symbolName : className;
            descEn = descCn;
        }

        List<String> keywords = new ArrayList<>();
        addCamelCaseWords(keywords, className);
        addCamelCaseWords(keywords, symbolName);
        for (String seg : safe(chunk.filePath()).split("[/\\\\]")) {
            if (!seg.isBlank() && !seg.endsWith(".java") && seg.length() > 2) {
                keywords.add(seg.toLowerCase());
            }
        }

        return chunk.withSemantics(descCn, descEn, chunk.callRelation(),
                keywords.stream().distinct().limit(15).toList());
    }

    private String classRoleHint(String className) {
        String lower = className.toLowerCase();
        if (lower.endsWith("model") || lower.endsWith("entity") || lower.endsWith("bean")) return " 数据模型";
        if (lower.endsWith("dto") || lower.endsWith("vo") || lower.endsWith("bo")) return " 传输对象";
        if (lower.endsWith("result") || lower.endsWith("response")) return " 响应结构";
        if (lower.endsWith("request") || lower.endsWith("param")) return " 请求参数";
        if (lower.endsWith("config") || lower.endsWith("cfg")) return " 配置类";
        if (lower.endsWith("util") || lower.endsWith("utils") || lower.endsWith("helper")) return " 工具类";
        if (lower.endsWith("enum") || lower.endsWith("type") || lower.endsWith("constant")) return " 枚举/常量";
        if (lower.endsWith("listener") || lower.endsWith("observer")) return " 事件监听";
        if (lower.endsWith("factory")) return " 工厂类";
        if (lower.endsWith("builder")) return " 构造器";
        return " 类";
    }

    private void addCamelCaseWords(List<String> list, String name) {
        if (name == null || name.isBlank()) return;
        String[] words = name.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .toLowerCase().split("\\s+");
        for (String w : words) {
            if (!w.isBlank() && w.length() > 1) list.add(w);
        }
    }

    private boolean containsAny(String text, String... words) {
        for (String w : words) {
            if (text.contains(w)) return true;
        }
        return false;
    }

    /** 为扫描得到的代码 chunk 批量附加语义元数据（无缓存）。 */
    public List<CodeChunk> annotate(List<CodeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return chunks == null ? List.of() : chunks;
        }
        try {
            int totalBatches = (chunks.size() + BATCH_SIZE - 1) / BATCH_SIZE;
            log.info("开始语义标注: {} 个 chunk, {} 批 (批次大小 {})", chunks.size(), totalBatches, BATCH_SIZE);
            long startMs = System.currentTimeMillis();
            List<CodeChunk> result = new ArrayList<>(chunks.size());
            int batchCount = 0;
            int consecutiveFailures = 0;
            boolean circuitOpen = false;
            for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, chunks.size());
                List<CodeChunk> batch = chunks.subList(i, end);
                batchCount++;
                long elapsedSec = (System.currentTimeMillis() - startMs) / 1000;
                long etaSec = batchCount > 1 ? elapsedSec * (totalBatches - batchCount) / (batchCount - 1) : 0;
                log.info("标注进度: {}/{} 批 ({}/{} chunks), 已用 {}s, 预计剩余 {}s",
                        batchCount, totalBatches, i, chunks.size(), elapsedSec, etaSec);
                if (circuitOpen) {
                    result.addAll(batch.stream().map(this::staticAnnotate).toList());
                    continue;
                }
                try {
                    result.addAll(annotateBatch(batch));
                    consecutiveFailures = 0;
                }
                catch (Exception ex) {
                    consecutiveFailures++;
                    log.warn("批次语义标注失败 ({}/{} consecutive, {} chunks): {}",
                            consecutiveFailures, MAX_CONSECUTIVE_LLM_FAILURES, batch.size(), ex.getMessage());
                    result.addAll(batch.stream().map(this::staticAnnotate).toList());
                    if (consecutiveFailures >= MAX_CONSECUTIVE_LLM_FAILURES) {
                        circuitOpen = true;
                        log.warn("语义标注熔断：本次索引剩余 {} 个核心 chunk 使用静态标注",
                                chunks.size() - end);
                    }
                }
            }
            long totalSec = (System.currentTimeMillis() - startMs) / 1000;
            log.info("语义标注完成: {} 个 chunk, 耗时 {}s", result.size(), totalSec);
            return result;
        }
        catch (Exception ex) {
            log.warn("代码语义标注失败，返回未标注的 chunks: {}", ex.getMessage());
            return chunks;
        }
    }

    List<CodeChunk> annotateBatch(List<CodeChunk> batch) throws Exception {
        String response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(buildUserPrompt(batch))
                .options(GenerationChatOptions.forModel(resolveModel()))
                .call()
                .content();
        List<SemanticAnnotation> annotations = parseAnnotations(response, batch.size());
        List<CodeChunk> enriched = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            CodeChunk chunk = batch.get(i);
            SemanticAnnotation annotation = annotations.get(i);
            enriched.add(chunk.withFullSemantics(
                    annotation.businessDescCn(),
                    annotation.businessDescEn(),
                    chunk.callRelation(),
                    annotation.keywords(),
                    annotation.userQuestions(),
                    annotation.synonyms()));
        }
        return enriched;
    }

    private String resolveModel() {
        return properties.llm().resolvedAnnotationModel();
    }

    private String buildUserPrompt(List<CodeChunk> batch) {
        StringBuilder prompt = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            CodeChunk chunk = batch.get(i);
            String className = safe(chunk.className());
            String symbolName = safe(chunk.symbolName());
            String symbolType = safe(chunk.symbolType());
            String text = safe(chunk.text());
            String preview = text.length() <= TEXT_PREVIEW_CHARS
                    ? text
                    : text.substring(0, TEXT_PREVIEW_CHARS);
            prompt.append('[').append(i).append("] ")
                    .append(className).append('.').append(symbolName)
                    .append(" (").append(symbolType).append("): ")
                    .append(preview);
            if (i < batch.size() - 1) {
                prompt.append('\n');
            }
        }
        return prompt.toString();
    }

    private List<SemanticAnnotation> parseAnnotations(String response, int expectedSize) throws Exception {
        String json = extractJsonArray(response);
        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray()) {
            throw new IllegalArgumentException("LLM 响应不是 JSON 数组");
        }
        if (root.size() != expectedSize) {
            throw new IllegalArgumentException("LLM 响应数组长度 " + root.size() + " 与输入 " + expectedSize + " 不一致");
        }
        List<SemanticAnnotation> annotations = new ArrayList<>(expectedSize);
        for (JsonNode node : root) {
            String businessDescCn = textOrEmpty(node, "businessDescCn");
            String businessDescEn = textOrEmpty(node, "businessDescEn");
            List<String> keywords = parseStringList(node.get("keywords"));
            List<String> userQuestions = parseStringList(node.get("userQuestions"));
            List<String> synonyms = parseStringList(node.get("synonyms"));
            annotations.add(new SemanticAnnotation(businessDescCn, businessDescEn, keywords, userQuestions, synonyms));
        }
        return annotations;
    }

    private String textOrEmpty(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText();
    }

    private List<String> parseStringList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            if (item != null && !item.isNull()) {
                String text = item.asText().trim();
                if (!text.isEmpty()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    private String extractJsonArray(String response) {
        if (response == null) {
            throw new IllegalArgumentException("LLM 响应为空");
        }
        String trimmed = response.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("LLM 响应中未找到 JSON 数组");
        }
        return trimmed.substring(start, end + 1);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record SemanticAnnotation(String businessDescCn, String businessDescEn,
                                      List<String> keywords, List<String> userQuestions,
                                      List<String> synonyms) {
    }
}
