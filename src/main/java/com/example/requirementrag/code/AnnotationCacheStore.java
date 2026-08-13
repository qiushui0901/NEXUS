package com.example.requirementrag.code;

import com.example.requirementrag.model.CodeChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码语义标注的磁盘持久化缓存（JSONL：sourceHash -> AnnotationEntry）。
 * 与 Qdrant payload 缓存互补：Qdrant collection 随发布替换/清理后，LLM 摘要仍可复用，
 * 同一源码的摘要只生成一次。
 */
@Component
public class AnnotationCacheStore {

    private static final Logger log = LoggerFactory.getLogger(AnnotationCacheStore.class);

    private final ObjectMapper objectMapper;
    private final Path root;

    @Autowired
    public AnnotationCacheStore(ObjectMapper objectMapper) {
        this(objectMapper, Path.of("data", "code-annotation-cache"));
    }

    AnnotationCacheStore(ObjectMapper objectMapper, Path root) {
        this.objectMapper = objectMapper;
        this.root = root;
    }

    /** 读取项目全部标注缓存；文件缺失或损坏时返回空缓存。 */
    public Map<String, CodeQdrantStore.AnnotationEntry> load(String projectId) {
        Path file = file(projectId);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Map<String, CodeQdrantStore.AnnotationEntry> result = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                String hash = node.path("sourceHash").asText("");
                if (hash.isBlank()) {
                    continue;
                }
                result.put(hash, new CodeQdrantStore.AnnotationEntry(
                        node.path("businessDescCn").asText(""),
                        node.path("businessDescEn").asText(""),
                        stringList(node, "keywords"),
                        stringList(node, "userQuestions"),
                        stringList(node, "synonyms")));
            }
        }
        catch (IOException | RuntimeException exception) {
            log.warn("读取标注磁盘缓存失败，使用空缓存: {}", exception.getMessage());
            return Map.of();
        }
        return result;
    }

    /** 追加写盘：仅写入带 LLM/静态摘要且缓存中不存在的条目。 */
    public synchronized void append(String projectId, List<CodeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        // load 对不存在文件返回不可变 Map.of()，必须包一层 HashMap 才能 put。
        Map<String, CodeQdrantStore.AnnotationEntry> existing = new HashMap<>(load(projectId));
        Path file = file(projectId);
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                for (CodeChunk chunk : chunks) {
                    if (blank(chunk.businessDescCn())) {
                        continue;
                    }
                    String hash = CodeQdrantStore.sourceHash(chunk.text());
                    if (existing.containsKey(hash)) {
                        continue;
                    }
                    existing.put(hash, new CodeQdrantStore.AnnotationEntry(
                            chunk.businessDescCn(), chunk.businessDescEn(),
                            chunk.keywords(), chunk.userQuestions(), chunk.synonyms()));
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("sourceHash", hash);
                    row.put("businessDescCn", chunk.businessDescCn());
                    row.put("businessDescEn", safe(chunk.businessDescEn()));
                    row.put("keywords", chunk.keywords() == null ? List.of() : chunk.keywords());
                    row.put("userQuestions", chunk.userQuestions() == null ? List.of() : chunk.userQuestions());
                    row.put("synonyms", chunk.synonyms() == null ? List.of() : chunk.synonyms());
                    writer.write(objectMapper.writeValueAsString(row));
                    writer.newLine();
                }
            }
        }
        catch (IOException | RuntimeException exception) {
            log.warn("写入标注磁盘缓存失败", exception);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private List<String> stringList(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        JsonNode array = node.get(field);
        if (array != null && array.isArray()) {
            array.forEach(item -> result.add(item.asText()));
        }
        return result;
    }

    private Path file(String projectId) {
        return root.resolve(projectId + ".jsonl");
    }
}
