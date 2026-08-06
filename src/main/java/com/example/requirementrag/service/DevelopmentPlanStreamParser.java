package com.example.requirementrag.service;

import com.example.requirementrag.model.DevelopmentPlanStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** 将模型增量文本按 NDJSON 行解析成开发方案事件。 */
public final class DevelopmentPlanStreamParser {
    private static final Logger log = LoggerFactory.getLogger(DevelopmentPlanStreamParser.class);

    private final ObjectMapper objectMapper;
    private final StringBuilder buffer = new StringBuilder();
    private long sequence;

    public DevelopmentPlanStreamParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 接收一段模型增量文本并解析出其中完整 NDJSON 行对应的事件；未凑成整行的内容留在缓冲区。
     *
     * @param chunk 模型输出的增量文本片段，可为 null 或空
     * @return 本次增量解析出的完整事件列表，可能为空
     */
    public List<DevelopmentPlanStreamEvent> accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return List.of();
        }
        buffer.append(chunk);
        List<DevelopmentPlanStreamEvent> events = new ArrayList<>();
        int newline;
        while ((newline = buffer.indexOf("\n")) >= 0) {
            String line = buffer.substring(0, newline);
            buffer.delete(0, newline + 1);
            parseLine(line).ifPresent(events::add);
        }
        return events;
    }

    /**
     * 流结束后解析缓冲区中最后一行不完整内容（通常缺结尾换行）。
     *
     * @return 缓冲区剩余内容对应的事件，空缓冲区或无有效行时返回空列表
     */
    public List<DevelopmentPlanStreamEvent> finish() {
        if (buffer.isEmpty()) {
            return List.of();
        }
        String line = buffer.toString();
        buffer.setLength(0);
        return parseLine(line).map(List::of).orElseGet(List::of);
    }

    /** 解析单行 NDJSON 为事件；空行、代码围栏、非对象或无 type 字段的行以及格式错误行均忽略。 */
    private java.util.Optional<DevelopmentPlanStreamEvent> parseLine(String rawLine) {
        String line = rawLine == null ? "" : rawLine.trim();
        if (line.isEmpty() || line.startsWith("```")) {
            return java.util.Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(line);
            if (root == null || !root.isObject() || !root.hasNonNull("type")) {
                return java.util.Optional.empty();
            }
            JsonNode payload = root.has("payload") ? root.get("payload") : objectMapper.createObjectNode();
            String message = root.hasNonNull("message") ? root.get("message").asText() : "";
            return java.util.Optional.of(new DevelopmentPlanStreamEvent(
                    root.get("type").asText(), ++sequence, payload, message));
        }
        catch (Exception exception) {
            log.debug("Ignoring a malformed development-plan stream line; content is omitted from logs", exception);
            return java.util.Optional.empty();
        }
    }
}
