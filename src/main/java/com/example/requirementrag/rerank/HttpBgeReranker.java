package com.example.requirementrag.rerank;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.ChunkRecord;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 通过 HTTP 调用 BGE 重排 API 的实现。
 */
public class HttpBgeReranker implements BgeReranker {

    static final int MAX_CHILD_CHARACTERS = 700;
    static final int MAX_PARENT_CONTEXT_CHARACTERS = 900;

    private final RestClient client;
    private final RagProperties.Bge properties;
    private final JsonMapper jsonMapper;
    private final boolean enrichedPassageEnabled;

    /** 注入 REST 客户端与 BGE 服务配置。 */
    public HttpBgeReranker(RestClient client, RagProperties.Bge properties, JsonMapper jsonMapper) {
        this(client, properties, jsonMapper, true);
    }

    /** 构造可显式回退到 0.8 child-only passage 的重排器。 */
    public HttpBgeReranker(RestClient client, RagProperties.Bge properties, JsonMapper jsonMapper,
                           boolean enrichedPassageEnabled) {
        this.client = client;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.enrichedPassageEnabled = enrichedPassageEnabled;
    }

    /** 调用 BGE API 对候选子块文本重排，按分数降序返回 topK。 */
    @Override
    public List<ChunkRecord> rerank(String query, List<ChunkRecord> candidates, int topK) {
        if (candidates.isEmpty()) return List.of();
        RestClient.RequestBodySpec request = client.post().uri(properties.path()).contentType(MediaType.APPLICATION_JSON);
        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
        }
        byte[] requestBody;
        try {
            requestBody = jsonMapper.writeValueAsBytes(new RerankRequest(
                    query, candidates.stream().map(this::requestPassage).toList(), true));
        }
        catch (RuntimeException exception) {
            throw new IllegalStateException("Unable to serialize BGE rerank request", exception);
        }
        List<Map<String, Object>> response = request.contentLength(requestBody.length).body(requestBody)
                .retrieve().body(new ParameterizedTypeReference<>() {});
        if (response == null) return List.of();
        List<Scored> scored = new ArrayList<>();
        for (Map<String, Object> item : response) {
            int index = ((Number) item.getOrDefault("index", 0)).intValue();
            double score = ((Number) item.getOrDefault("score", item.getOrDefault("relevance_score", 0))).doubleValue();
            if (index >= 0 && index < candidates.size()) scored.add(new Scored(candidates.get(index), score));
        }
        return scored.stream().sorted(Comparator.comparingDouble(Scored::score).reversed()).limit(topK).map(Scored::chunk).toList();
    }


    /** 根据配置选择发送富化 passage（child + parent 上下文）还是仅 child 文本。 */
    private String requestPassage(ChunkRecord chunk) {
        return enrichedPassageEnabled ? passage(chunk) : normalize(chunk.childText());
    }

    /** 拼接 filename、child 文本与按 child 居中的 parent 上下文，构造富化 passage。 */
    static String passage(ChunkRecord chunk) {
        String child = bounded(normalize(chunk.childText()), MAX_CHILD_CHARACTERS);
        String parent = parentContext(normalize(chunk.parentText()), child, MAX_PARENT_CONTEXT_CHARACTERS);
        return "file: " + normalize(chunk.filename())
                + "\nmatching child:\n" + child
                + "\nparent context:\n" + parent;
    }

    /** 超长 parent 截断为 limit 长度，截断窗口以 child 匹配位置为中心，避免截掉相关内容。 */
    private static String parentContext(String parent, String child, int limit) {
        if (parent.length() <= limit) return parent;
        int match = child.isBlank() ? -1 : parent.indexOf(child);
        int center = match < 0 ? 0 : match + child.length() / 2;
        int start = Math.max(0, Math.min(center - limit / 2, parent.length() - limit));
        return parent.substring(start, start + limit);
    }

    /** 超出 limit 时从开头截断。 */
    private static String bounded(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /** 将连续空白折叠为单个空格并去除首尾空白；null 视为空串。 */
    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    /** 带相关性分数的分块记录。 */
    private record Scored(ChunkRecord chunk, double score) {
    }

    private record RerankRequest(String query, List<String> texts, boolean truncate) {
    }
}
