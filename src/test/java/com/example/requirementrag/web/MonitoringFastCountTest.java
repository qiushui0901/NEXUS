package com.example.requirementrag.web;

import com.example.requirementrag.code.CodeQdrantStore;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.retrieval.EmbeddingBatcher;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.retrieval.SparseVectorizer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MonitoringFastCountTest {

    @Test
    void requirementMonitoringCountSkipsCollectionInitialization() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost/collections/requirements/points/count"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"result\":{\"count\":7}}", MediaType.APPLICATION_JSON));
        QdrantHybridStore store = new QdrantHybridStore(builder.baseUrl("http://localhost").build(),
                mock(EmbeddingModel.class), mock(EmbeddingBatcher.class),
                new SparseVectorizer(), mock(RagProperties.class));

        assertThat(store.countVersionIfAvailable("requirements", "doc", "5.1")).isEqualTo(7);
        server.verify();
    }

    @Test
    void codeMonitoringCountSkipsCollectionInitialization() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost/collections/code/points/count"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"result\":{\"count\":11}}", MediaType.APPLICATION_JSON));
        CodeQdrantStore store = new CodeQdrantStore(builder.baseUrl("http://localhost").build(),
                mock(EmbeddingModel.class), mock(EmbeddingBatcher.class),
                new SparseVectorizer(), mock(RagProperties.class));

        assertThat(store.countProjectIfAvailable("code", "project-a")).isEqualTo(11);
        server.verify();
    }
}
