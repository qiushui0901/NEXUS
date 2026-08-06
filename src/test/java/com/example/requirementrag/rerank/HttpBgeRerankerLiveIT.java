package com.example.requirementrag.rerank;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.ChunkRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Live, opt-in contract check for the local Python/Transformers BGE service. */
@EnabledIfEnvironmentVariable(named = "RUN_BGE_LIVE_CONTRACT", matches = "true")
class HttpBgeRerankerLiveIT {

    @Test
    void productionJavaClientCanRankAgainstLocalTransformersService() {
        String baseUrl = requiredEnvironment("BGE_RERANK_URL");
        String path = requiredEnvironment("BGE_RERANK_PATH");
        String apiKey = System.getenv().getOrDefault("BGE_RERANK_API_KEY", "");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(120));
        HttpBgeReranker reranker = new HttpBgeReranker(
                RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build(),
                new RagProperties.Bge(baseUrl, path, apiKey),
                JsonMapper.builder().build());
        ChunkRecord matching = chunk("matching", "local health check");
        ChunkRecord unrelated = chunk("unrelated", "unrelated text");

        List<ChunkRecord> ranked = reranker.rerank(
                "local health check", List.of(unrelated, matching), 2);

        assertThat(ranked).containsExactly(matching, unrelated);
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertThat(value).as(name + " must be frozen by the evaluation runner").isNotBlank();
        return value;
    }

    private ChunkRecord chunk(String id, String childText) {
        return new ChunkRecord(id, "requirements", "5.1", id + ".md", "parent-" + id,
                "parent text", childText, "hash-" + id, 0, 0);
    }
}
