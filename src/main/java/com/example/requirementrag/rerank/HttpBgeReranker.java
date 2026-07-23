package com.example.requirementrag.rerank;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.ChunkRecord;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 通过 HTTP 调用 BGE 重排 API 的实现。
 */
public class HttpBgeReranker implements BgeReranker {

    private final RestClient client;
    private final RagProperties.Bge properties;

    /** 注入 REST 客户端与 BGE 服务配置。 */
    public HttpBgeReranker(RestClient client, RagProperties.Bge properties) {
        this.client = client;
        this.properties = properties;
    }

    /** 调用 BGE API 对候选子块文本重排，按分数降序返回 topK。 */
    @Override
    public List<ChunkRecord> rerank(String query, List<ChunkRecord> candidates, int topK) {
        if (candidates.isEmpty()) return List.of();
        RestClient.RequestBodySpec request = client.post().uri(properties.path()).contentType(MediaType.APPLICATION_JSON);
        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
        }
        List<Map<String, Object>> response = request.body(Map.of(
                        "query", query,
                        "texts", candidates.stream().map(ChunkRecord::childText).toList(),
                        "truncate", true))
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

    /** 带相关性分数的分块记录。 */
    private record Scored(ChunkRecord chunk, double score) {
    }
}
