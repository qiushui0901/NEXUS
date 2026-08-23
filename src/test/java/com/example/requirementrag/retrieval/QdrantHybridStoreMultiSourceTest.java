package com.example.requirementrag.retrieval;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.ChunkRecord;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class QdrantHybridStoreMultiSourceTest {

    private RagProperties retrievalProperties() {
        RagProperties properties = mock(RagProperties.class);
        when(properties.retrieval()).thenReturn(new RagProperties.Retrieval(
                50, 50, 40, 20, 10, false, 1_000, 3, 3, 30_000,
                -1, -1, -1, -1, null, null, null, true, 3));
        return properties;
    }

    private QdrantHybridStore store(RestClient client, RagProperties properties,
                                    EmbeddingBatcher batcher) {
        return new QdrantHybridStore(client,
                mock(EmbeddingModel.class), batcher,
                new SparseVectorizer(), properties);
    }

    @Test
    void hybridSearchWithSourceTypesSendsSourceFilterAndReadsPayload() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        RagProperties properties = retrievalProperties();
        EmbeddingBatcher batcher = mock(EmbeddingBatcher.class);
        when(batcher.embedAll(any())).thenReturn(List.of(new float[2], new float[2]));
        QdrantHybridStore store = store(client, properties, batcher);

        server.expect(requestTo(startsWith("/collections/requirements-live")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"result\": {\"exists\": true}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/points/query")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.prefetch[0].filter.must[2].key").value("sourceType"))
                .andExpect(jsonPath("$.prefetch[0].filter.must[2].match.value").value("REQUIREMENT"))
                .andRespond(withSuccess("""
                        {"result": {"points": [{"id": "point-1", "score": 0.9, "payload": {
                          "documentId": "doc-a", "version": "1.0", "filename": "req.md",
                          "parentId": "p1", "parentText": "", "childText": "text",
                          "contentHash": "hash", "parentOrder": 0, "childOrder": 0,
                          "sourceType": "REQUIREMENT"
                        }}]}}
                        """, MediaType.APPLICATION_JSON));

        List<ChunkRecord> result = store.hybridSearch(
                "requirements-live", "查询", "doc-a", "1.0", Set.of("REQUIREMENT"));

        org.assertj.core.api.Assertions.assertThat(result).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(result.get(0).sourceType()).isEqualTo("REQUIREMENT");
        server.verify();
    }

    @Test
    void publishLiveAliasWritesVersionedCollectionAndCreatesAliasAtomically() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        RagProperties properties = retrievalProperties();
        EmbeddingBatcher batcher = mock(EmbeddingBatcher.class);
        when(batcher.embedAll(any())).thenReturn(List.of(new float[2], new float[2]));
        QdrantHybridStore store = store(client, properties, batcher);

        server.expect(requestTo(startsWith("/collections/requirements_live-")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(startsWith("/collections/requirements_live-")))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());
        server.expect(requestTo(containsString("/points?wait=true")))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());
        server.expect(requestTo(containsString("/points/count")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"result\": {\"count\": 2}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/aliases")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"result\": {\"aliases\": []}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/collections/aliases")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());
        server.expect(requestTo(containsString("/collections/requirements_live")))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());
        server.expect(requestTo(containsString("/collections")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"result\": {\"collections\": []}}", MediaType.APPLICATION_JSON));

        store.publishLiveAlias("requirements_live", List.of(
                chunk("r1", "REQUIREMENT"),
                chunk("r2", "REQUIREMENT")));

        server.verify();
    }

    @Test
    void rollbackLiveAliasDeletesAndRecreatesAliasInSingleAtomicRequest() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        RagProperties properties = retrievalProperties();
        EmbeddingBatcher batcher = mock(EmbeddingBatcher.class);
        QdrantHybridStore store = store(client, properties, batcher);

        server.expect(requestTo(containsString("/aliases")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"result": {"aliases": [{"alias_name": "requirements_live", "collection_name": "bad"}]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/collections/aliases")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.actions.length()").value(2))
                .andExpect(jsonPath("$.actions[0].delete_alias.alias_name").value("requirements_live"))
                .andExpect(jsonPath("$.actions[1].create_alias.collection_name").value("requirements_live-1"))
                .andRespond(withSuccess());

        store.rollbackLiveAlias("requirements_live", "requirements_live-1");

        server.verify();
    }

    @Test
    void publishFallbackSwitchsAliasWithSingleAtomicDeleteCreateRequest() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        RagProperties properties = retrievalProperties();
        EmbeddingBatcher batcher = mock(EmbeddingBatcher.class);
        when(batcher.embedAll(any())).thenReturn(List.of(new float[2], new float[2]));
        QdrantHybridStore store = store(client, properties, batcher);

        server.expect(requestTo(startsWith("/collections/requirements_live-")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(startsWith("/collections/requirements_live-")))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());
        server.expect(requestTo(containsString("/points?wait=true")))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());
        server.expect(requestTo(containsString("/points/count")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"result\": {\"count\": 2}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/aliases")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"result": {"aliases": [{"alias_name": "requirements_live", "collection_name": "old"}]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/collections/aliases")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.actions[0].swap_aliases").exists())
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(requestTo(containsString("/collections/aliases")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.actions.length()").value(2))
                .andExpect(jsonPath("$.actions[0].delete_alias.alias_name").value("requirements_live"))
                .andExpect(jsonPath("$.actions[1].create_alias.collection_name").exists())
                .andRespond(withSuccess());
        server.expect(requestTo(containsString("/collections")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"result\": {\"collections\": []}}", MediaType.APPLICATION_JSON));

        store.publishLiveAlias("requirements_live", List.of(
                chunk("r1", "REQUIREMENT"),
                chunk("r2", "REQUIREMENT")));

        server.verify();
    }

    @Test
    void aliasRemainsQueryableWhenAtomicFallbackRequestFails() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        RagProperties properties = retrievalProperties();
        EmbeddingBatcher batcher = mock(EmbeddingBatcher.class);
        when(batcher.embedAll(any())).thenReturn(List.of(new float[2], new float[2]));
        QdrantHybridStore store = store(client, properties, batcher);

        server.expect(requestTo(startsWith("/collections/requirements_live-")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(startsWith("/collections/requirements_live-")))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());
        server.expect(requestTo(containsString("/points?wait=true")))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());
        server.expect(requestTo(containsString("/points/count")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"result\": {\"count\": 2}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/aliases")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"result": {"aliases": [{"alias_name": "requirements_live", "collection_name": "old"}]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/collections/aliases")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.actions[0].swap_aliases").exists())
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(requestTo(containsString("/collections/aliases")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.actions[0].delete_alias").exists())
                .andExpect(jsonPath("$.actions[1].create_alias").exists())
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo(containsString("/aliases")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"result": {"aliases": [{"alias_name": "requirements_live", "collection_name": "old"}]}}
                        """, MediaType.APPLICATION_JSON));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                store.publishLiveAlias("requirements_live", List.of(
                        chunk("r1", "REQUIREMENT"),
                        chunk("r2", "REQUIREMENT"))))
                .isInstanceOf(RuntimeException.class);
        org.assertj.core.api.Assertions.assertThat(store.aliasTarget("requirements_live"))
                .isEqualTo("old");

        server.verify();
    }

    private ChunkRecord chunk(String id, String sourceType) {
        return new ChunkRecord(id, "doc-a", "1.0", "req.md", "parent-" + id,
                "parent", "child " + id, "hash-" + id, 0, 0, "", "", "", "", "REQUIREMENT",
                sourceType);
    }
}