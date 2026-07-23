package com.example.requirementrag.service;

import com.example.requirementrag.model.DevelopmentPlanStreamEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** 将模型增量文本按 NDJSON 行解析成开发方案事件。 */
public final class DevelopmentPlanStreamParser {

    private final ObjectMapper objectMapper;
    private final StringBuilder buffer = new StringBuilder();
    private long sequence;

    public DevelopmentPlanStreamParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

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

    public List<DevelopmentPlanStreamEvent> finish() {
        if (buffer.isEmpty()) {
            return List.of();
        }
        String line = buffer.toString();
        buffer.setLength(0);
        return parseLine(line).map(List::of).orElseGet(List::of);
    }

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
        catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }
}
