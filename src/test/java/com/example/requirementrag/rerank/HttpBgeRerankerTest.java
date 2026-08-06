package com.example.requirementrag.rerank;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.ChunkRecord;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpBgeRerankerTest {

    @Test
    void sendsTransformersServiceContractAndReturnsHighestScoresFirst() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8081");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpBgeReranker reranker = new HttpBgeReranker(builder.build(),
                new RagProperties.Bge("http://localhost:8081", "/rerank", "secret"),
                JsonMapper.builder().build());
        ChunkRecord first = chunk("first", "first passage");
        ChunkRecord second = chunk("second", "second passage");
        ChunkRecord third = chunk("third", "third passage");

        server.expect(once(), requestTo("http://localhost:8081/rerank"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret"))
                .andExpect(request -> assertThat(request.getHeaders().getContentLength()).isPositive())
                .andExpect(request -> assertThat(request.getHeaders().getFirst(HttpHeaders.TRANSFER_ENCODING)).isNull())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "query": "target query",
                          "texts": [
                            "file: first.md\nmatching child:\nfirst passage\nparent context:\nparent text",
                            "file: second.md\nmatching child:\nsecond passage\nparent context:\nparent text",
                            "file: third.md\nmatching child:\nthird passage\nparent context:\nparent text"
                          ],
                          "truncate": true
                        }
                        """))
                .andRespond(withSuccess("""
                        [
                          {"index": 0, "score": 0.25},
                          {"index": 1, "score": 0.91},
                          {"index": 2, "relevance_score": 0.66},
                          {"index": 99, "score": 1.0}
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<ChunkRecord> result = reranker.rerank("target query", List.of(first, second, third), 2);

        assertThat(result).containsExactly(second, third);
        server.verify();
    }

    @Test
    void omitsAuthorizationHeaderWhenApiKeyIsBlank() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8081");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpBgeReranker reranker = new HttpBgeReranker(builder.build(),
                new RagProperties.Bge("http://localhost:8081", "/rerank", " "),
                JsonMapper.builder().build());

        server.expect(once(), requestTo("http://localhost:8081/rerank"))
                .andExpect(request -> assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull())
                .andRespond(withSuccess("[{\"index\":0,\"score\":0.8}]", MediaType.APPLICATION_JSON));

        assertThat(reranker.rerank("query", List.of(chunk("first", "passage")), 1))
                .extracting(ChunkRecord::id)
                .containsExactly("first");
        server.verify();
    }

    @Test
    void passageKeepsMatchingChildBeforeBoundedParentContext() {
        String child = "TARGET-CHILD-" + "c".repeat(900);
        ChunkRecord chunk = new ChunkRecord("id", "requirements", "5.1", "feature.md", "parent",
                "prefix-" + "p".repeat(1_200) + child + "-suffix", child, "hash", 0, 0);

        String passage = HttpBgeReranker.passage(chunk);

        assertThat(passage)
                .startsWith("file: feature.md\nmatching child:\nTARGET-CHILD-")
                .contains("\nparent context:\n")
                .hasSizeLessThanOrEqualTo(
                        HttpBgeReranker.MAX_CHILD_CHARACTERS
                                + HttpBgeReranker.MAX_PARENT_CONTEXT_CHARACTERS + 80);
    }

    private ChunkRecord chunk(String id, String childText) {
        return new ChunkRecord(id, "requirements", "5.1", id + ".md", "parent-" + id,
                "parent text", childText, "hash-" + id, 0, 0);
    }
}
