package com.example.requirementrag.code;

import com.example.requirementrag.model.CodeChunk;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeQdrantStoreTest {

    @Test
    void retrievalTextFrontLoadsSymbolPathAndImplementationRoleWithoutChangingPayloadText() {
        CodeChunk chunk = new CodeChunk("id", "demo", "abc",
                "module/src/main/java/com/acme/note/service/impl/NoteServiceImpl.java",
                "method", "publishNote", 10, 30,
                "public void publishNote() { persist(); }", "hash", "java");

        String retrievalText = CodeQdrantStore.retrievalText(chunk);

        assertThat(retrievalText)
                .startsWith("file path: module/src/main/java/com/acme/note/service/impl/NoteServiceImpl.java")
                .contains("symbol type: method")
                .contains("symbol name: publishNote")
                .contains("symbol terms: publish Note")
                .contains("service implementation business logic")
                .contains("服务实现 业务逻辑 实现入口")
                .endsWith(chunk.text());
        assertThat(chunk.text()).isEqualTo("public void publishNote() { persist(); }");
    }

    @Test
    void retrievalTextClassifiesControllerAndTestRolesDeterministically() {
        CodeChunk controller = chunk("src/main/java/com/acme/web/controller/NoteController.java", "publishNote");
        CodeChunk test = chunk("src/test/java/com/acme/note/NoteServiceTest.java", "publishNote");

        assertThat(CodeQdrantStore.retrievalText(controller)).contains("controller api endpoint request entry");
        assertThat(CodeQdrantStore.retrievalText(test)).contains("test verification");
    }


    @Test
    void denseRetrievalTextBoundsLongSourceButKeepsMetadataAtTheFront() {
        String longSource = "x".repeat(2_000) + "TAIL_MARKER";
        CodeChunk chunk = new CodeChunk("id", "demo", "abc",
                "src/main/java/com/acme/service/impl/NoteServiceImpl.java",
                "method", "publishNote", 1, 200, longSource, "hash", "java");

        String denseText = CodeQdrantStore.denseRetrievalText(chunk);

        assertThat(denseText)
                .startsWith("file path: src/main/java/com/acme/service/impl/NoteServiceImpl.java")
                .contains("symbol name: publishNote")
                .contains("source code prefix:")
                .doesNotContain("TAIL_MARKER");
    }

    @Test
    void rerankCandidatesPromotesServiceImplementationForExplicitRoleIntent() {
        CodeChunk controller = chunk("src/main/java/com/acme/controller/AgentController.java", "chat");
        CodeChunk helper = chunk("src/main/java/com/acme/tool/AgentTools.java", "searchRelatedTopics");
        CodeChunk implementation = chunk("src/main/java/com/acme/service/impl/AgentServiceImpl.java", "chat");

        List<CodeChunk> reranked = CodeQdrantStore.rerankCandidates(
                "AI 普通对话服务实现入口", List.of(controller, helper, implementation), 3);

        assertThat(reranked).containsExactly(implementation, controller, helper);
    }

    @Test
    void rerankCandidatesExpandsBilingualIntentIntoStableSymbolTerms() {
        CodeChunk noise = chunk("src/main/java/com/acme/search/CanalSchedule.java", "syncUserIndex");
        CodeChunk userSearch = chunk("src/main/java/com/acme/search/service/impl/UserServiceImpl.java", "searchUser");
        CodeChunk chat = chunk("src/main/java/com/acme/agent/service/impl/AgentServiceImpl.java", "chat");

        assertThat(CodeQdrantStore.rerankCandidates(
                "构建用户搜索 Wiki 且不能混入笔记搜索", List.of(noise, userSearch), 2))
                .startsWith(userSearch);
        assertThat(CodeQdrantStore.rerankCandidates(
                "AI 普通对话复用受控模型调用边界", List.of(noise, chat), 2))
                .startsWith(chat);
    }

    @Test
    void queryExpansionIsBoundedToKnownIntentPhrases() {
        assertThat(CodeQdrantStore.expandQuery("用户搜索和搜索摘要"))
                .contains("search user", "search summary");
        assertThat(CodeQdrantStore.expandQuery("无关查询")).isEqualTo("无关查询");
    }

    @Test
    void rerankCandidatesPreservesOriginalOrderWithoutStructuralOrLexicalSignal() {
        CodeChunk first = chunk("src/main/java/com/acme/Alpha.java", "alpha");
        CodeChunk second = chunk("src/main/java/com/acme/Beta.java", "beta");

        assertThat(CodeQdrantStore.rerankCandidates("无关查询", List.of(first, second), 2))
                .containsExactly(first, second);
    }

    @Test
    void rerankCandidatesStillHonorsRequestedLimit() {
        CodeChunk first = chunk("src/main/java/com/acme/Alpha.java", "alpha");
        CodeChunk exact = chunk("src/main/java/com/acme/Beta.java", "publishNote");

        assertThat(CodeQdrantStore.rerankCandidates("publishNote implementation", List.of(first, exact), 1))
                .containsExactly(exact);
    }

    @Test
    void codeSearchTraceDefensivelyCopiesBothStages() {
        CodeChunk candidate = chunk("src/main/java/com/acme/Alpha.java", "alpha");
        CodeChunk ranked = chunk("src/main/java/com/acme/Beta.java", "beta");
        java.util.ArrayList<CodeChunk> candidates = new java.util.ArrayList<>(List.of(candidate));
        java.util.ArrayList<CodeChunk> rankedValues = new java.util.ArrayList<>(List.of(ranked));

        CodeQdrantStore.CodeSearchTrace trace =
                new CodeQdrantStore.CodeSearchTrace(candidates, rankedValues);
        candidates.clear();
        rankedValues.clear();

        assertThat(trace.candidates()).containsExactly(candidate);
        assertThat(trace.ranked()).containsExactly(ranked);
        assertThat(trace.denseCandidates()).isEmpty();
        assertThat(trace.sparseCandidates()).isEmpty();
        assertThatThrownBy(() -> trace.candidates().add(ranked))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void codeSearchTraceDefensivelyCopiesPrefetchStages() {
        CodeChunk dense = chunk("src/main/java/com/acme/Alpha.java", "alpha");
        CodeChunk sparse = chunk("src/main/java/com/acme/Beta.java", "beta");
        java.util.ArrayList<CodeChunk> denseValues = new java.util.ArrayList<>(List.of(dense));
        java.util.ArrayList<CodeChunk> sparseValues = new java.util.ArrayList<>(List.of(sparse));

        CodeQdrantStore.CodeSearchTrace trace = new CodeQdrantStore.CodeSearchTrace(
                List.of(), List.of(), denseValues, sparseValues);
        denseValues.clear();
        sparseValues.clear();

        assertThat(trace.denseCandidates()).containsExactly(dense);
        assertThat(trace.sparseCandidates()).containsExactly(sparse);
    }

    @Test
    void semanticRerankReordersCandidatesByBgeScoreAndDropsUnknownIds() {
        CodeChunk first = chunk("src/main/java/com/acme/Alpha.java", "alpha", "id-1");
        CodeChunk second = chunk("src/main/java/com/acme/Beta.java", "beta", "id-2");
        CodeChunk third = chunk("src/main/java/com/acme/Gamma.java", "gamma", "id-3");
        com.example.requirementrag.rerank.BgeReranker bge = mock(com.example.requirementrag.rerank.BgeReranker.class);
        when(bge.rerank(eq("query"), any(), eq(3)))
                .thenReturn(List.of(
                        new com.example.requirementrag.model.ChunkRecord(third.id(), "demo", "abc",
                                third.filePath(), null, "", "passage", "hash", 1, 2),
                        new com.example.requirementrag.model.ChunkRecord(second.id(), "demo", "abc",
                                second.filePath(), null, "", "passage", "hash", 1, 2)));
        CodeQdrantStore store = storeWith(bge);

        assertThat(store.semanticRerank("query", List.of(first, second, third)))
                .containsExactly(third, second);
    }

    @Test
    void semanticRerankFallsBackToRrfOrderWhenBgeUnavailable() {
        CodeChunk first = chunk("src/main/java/com/acme/Alpha.java", "alpha");
        CodeChunk second = chunk("src/main/java/com/acme/Beta.java", "beta");
        com.example.requirementrag.rerank.BgeReranker bge = mock(com.example.requirementrag.rerank.BgeReranker.class);
        when(bge.rerank(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("endpoint unavailable"));
        CodeQdrantStore store = storeWith(bge);

        assertThat(store.semanticRerank("query", List.of(first, second)))
                .containsExactly(first, second);
    }

    @Test
    void semanticRerankPassesRetrievalTextAsBgePassage() {
        CodeChunk chunk = chunk("src/main/java/com/acme/service/impl/NoteServiceImpl.java", "publishNote");
        com.example.requirementrag.rerank.BgeReranker bge = mock(com.example.requirementrag.rerank.BgeReranker.class);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<com.example.requirementrag.model.ChunkRecord>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        when(bge.rerank(eq("query"), captor.capture(), anyInt())).thenReturn(List.of());
        CodeQdrantStore store = storeWith(bge);

        store.semanticRerank("query", List.of(chunk));

        assertThat(captor.getValue().get(0).childText())
                .startsWith("file path: src/main/java/com/acme/service/impl/NoteServiceImpl.java");
        assertThat(captor.getValue().get(0).filename()).isEqualTo(chunk.filePath());
    }

    @Test
    void semanticRerankPrunesInputToBgeTopKBeforeScoring() {
        java.util.ArrayList<CodeChunk> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            candidates.add(chunk("src/main/java/com/acme/Alpha.java", "alpha" + i, "id-" + i));
        }
        com.example.requirementrag.rerank.BgeReranker bge = mock(com.example.requirementrag.rerank.BgeReranker.class);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.List<com.example.requirementrag.model.ChunkRecord>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        when(bge.rerank(eq("query"), captor.capture(), eq(20))).thenReturn(List.of());
        CodeQdrantStore store = storeWith(bge);

        store.semanticRerank("query", candidates);

        assertThat(captor.getValue()).hasSize(20);
        assertThat(captor.getValue().get(0).id()).isEqualTo("id-0");
        assertThat(captor.getValue().get(19).id()).isEqualTo("id-19");
    }

    private CodeQdrantStore storeWith(com.example.requirementrag.rerank.BgeReranker bge) {
        com.example.requirementrag.config.RagProperties properties =
                mock(com.example.requirementrag.config.RagProperties.class);
        when(properties.retrieval()).thenReturn(new com.example.requirementrag.config.RagProperties.Retrieval(
                50, 50, 40, 20, 10, false, 1_000, 3, 3, 30_000,
                -1, -1, -1, -1, null, null, null, true, 3));
        return new CodeQdrantStore(mock(org.springframework.web.client.RestClient.class),
                mock(org.springframework.ai.embedding.EmbeddingModel.class),
                mock(com.example.requirementrag.retrieval.EmbeddingBatcher.class),
                new com.example.requirementrag.retrieval.SparseVectorizer(),
                properties, bge);
    }

    @Test
    void idempotentQueryRetriesOneTransientRequestBodyIoFailure() {
        AtomicInteger attempts = new AtomicInteger();

        String result = CodeQdrantStore.executeIdempotentQuery(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new HttpMessageNotWritableException("Could not write JSON",
                        new IOException("Error writing request body to server"));
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void idempotentQueryStopsAfterOneRetryWhenIoFailurePersists() {
        AtomicInteger attempts = new AtomicInteger();
        ResourceAccessException failure = new ResourceAccessException("Qdrant unavailable",
                new IOException("connection reset"));

        assertThatThrownBy(() -> CodeQdrantStore.executeIdempotentQuery(() -> {
            attempts.incrementAndGet();
            throw failure;
        })).isSameAs(failure);

        assertThat(attempts).hasValue(2);
    }

    @Test
    void idempotentQueryDoesNotRetryHttpStatusFailures() {
        AtomicInteger attempts = new AtomicInteger();
        HttpClientErrorException failure = new HttpClientErrorException(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> CodeQdrantStore.executeIdempotentQuery(() -> {
            attempts.incrementAndGet();
            throw failure;
        })).isSameAs(failure);

        assertThat(attempts).hasValue(1);
    }

    private CodeChunk chunk(String path, String symbolName) {
        return chunk(path, symbolName, "id");
    }

    private CodeChunk chunk(String path, String symbolName, String id) {
        return new CodeChunk(id, "demo", "abc", path, "method", symbolName,
                1, 2, "void method() {}", "hash", "java");
    }
    @Test
    void publishProjectWritesVersionedCollectionVerifiesAndCreatesAliasAtomically() throws Exception {
        org.springframework.web.client.RestClient.Builder builder = org.springframework.web.client.RestClient.builder();
        org.springframework.test.web.client.MockRestServiceServer server =
                org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        org.springframework.web.client.RestClient client = builder.build();
        com.example.requirementrag.config.RagProperties properties =
                mock(com.example.requirementrag.config.RagProperties.class);
        when(properties.retrieval()).thenReturn(new com.example.requirementrag.config.RagProperties.Retrieval(
                50, 50, 40, 20, 10, false, 1_000, 3, 3, 30_000,
                -1, -1, -1, -1, null, null, null, true, 3));


        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.startsWith("/collections/code_x-live-")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.GET))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(HttpStatus.NOT_FOUND));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.startsWith("/collections/code_x-live-")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.PUT))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess());
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.containsString("/points?wait=true")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.PUT))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess());
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.containsString("/points/count")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\": {\"count\": 2}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.containsString("/aliases")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\": {\"aliases\": []}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.containsString("/collections/aliases")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess());
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.containsString("/collections/code_x-live")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess());
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(org.hamcrest.Matchers.containsString("/collections")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.GET))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"result\": {\"collections\": [{\"name\": \"code_x-live-1\"}, {\"name\": \"code_x-live-2\"}]}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        List<CodeChunk> chunks = List.of(
                chunk("src/Alpha.java", "run"),
                chunk("src/Beta.java", "stop"));
        com.example.requirementrag.retrieval.EmbeddingBatcher batcher =
                mock(com.example.requirementrag.retrieval.EmbeddingBatcher.class);
        when(batcher.embedAll(any())).thenReturn(List.of(new float[2], new float[2]));
        com.example.requirementrag.config.RagProperties props =
                mock(com.example.requirementrag.config.RagProperties.class);
        when(props.retrieval()).thenReturn(new com.example.requirementrag.config.RagProperties.Retrieval(
                50, 50, 40, 20, 10, false, 1_000, 3, 3, 30_000,
                -1, -1, -1, -1, null, null, null, true, 3));
        CodeQdrantStore store = new CodeQdrantStore(client,
                mock(org.springframework.ai.embedding.EmbeddingModel.class),
                batcher,
                new com.example.requirementrag.retrieval.SparseVectorizer(),
                props, null);

        store.publishProject("code_x-live", "demo", chunks);

        server.verify();
    }
}
